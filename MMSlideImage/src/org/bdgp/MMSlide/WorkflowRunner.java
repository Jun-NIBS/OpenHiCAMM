package org.bdgp.MMSlide;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.logging.Handler;
import java.util.logging.Level;

import org.bdgp.MMSlide.DB.Config;
import org.bdgp.MMSlide.DB.ModuleConfig;
import org.bdgp.MMSlide.DB.Task;
import org.bdgp.MMSlide.DB.TaskConfig;
import org.bdgp.MMSlide.DB.TaskDispatch;
import org.bdgp.MMSlide.DB.WorkflowInstance;
import org.bdgp.MMSlide.DB.WorkflowModule;
import org.bdgp.MMSlide.DB.Task.Status;
import org.bdgp.MMSlide.DB.WorkflowModule.TaskType;
import org.bdgp.MMSlide.Modules.Interfaces.Module;
import org.bdgp.MMSlide.Modules.Interfaces.TaskListener;

import com.j256.ormlite.support.DatabaseConnection;

import static org.bdgp.MMSlide.Util.where;

public class WorkflowRunner {
    /**
     * Default file names for the metadata files.
     */
    public static final String WORKFLOW_DB = "workflow.db";
    public static final String LOG_FILE = "log.txt";
    
    private Connection workflowDb;
    private Connection instanceDb;
    
    private File workflowDirectory;
    private WorkflowInstance instance;
    
    private Dao<WorkflowModule> workflow;
    private Dao<ModuleConfig> moduleConfig;
    private Dao<WorkflowInstance> workflowInstance;
    private Dao<TaskDispatch> taskDispatch;
    private Dao<TaskConfig> taskConfig;
    private Dao<Task> taskStatus;
    
    private Map<String,Module> moduleInstances;
    
    private List<Handler> logHandlers;
    private Level logLevel;
    
    private ExecutorService pool;
    private int maxThreads;
    private Map<String,Semaphore> resources;
    
    private List<TaskListener> taskListeners;
    private MMSlide mmslide;
    
    /**
     * Constructor for WorkflowRunner. This loads the workflow.txt file 
     * in the workflow directory and performs some consistency checks 
     * on the workflow described in the file.
     * @param workflowDirectory
     */
    public WorkflowRunner(
            File workflowDirectory, 
            Integer instanceId, 
            Map<String,Integer> resources,
            Level loglevel,
            MMSlide mmslide) 
    {
        // Load the workflow database and workflow table
        if (workflowDirectory == null || !workflowDirectory.exists() || !workflowDirectory.isDirectory()) {
            throw new RuntimeException("Directory "+workflowDirectory+" is not a valid directory.");
        }
        this.workflowDb = Connection.get(new File(workflowDirectory, WORKFLOW_DB).getPath());
        Dao<WorkflowModule> workflow = this.workflowDb.table(WorkflowModule.class);
        
        this.moduleInstances = new HashMap<String,Module>();
        for (WorkflowModule w : workflow.select()) {
            // Make sure parent IDs are defined
            if (w.getParentId() != null) {
                List<WorkflowModule> parent = workflow.select(where("id", w.getParentId()));
                if (parent.size() == 0) {
                    throw new RuntimeException("Workflow references unknown parent ID "+w.getParentId());
                }
            }
            // Make sure all modules implement Module
            if (!Module.class.isAssignableFrom(w.getModule())) {
                throw new RuntimeException("First module "+w.getModuleName()
                        +" in Workflow does not inherit the Module interface.");
            }
            // Instantiate the module instances and put them in a hash
            try { 
                Module m = w.getModule().newInstance();
                m.initialize(this, w.getId());
                moduleInstances.put(w.getId(), m); 
            } 
            catch (InstantiationException e) {throw new RuntimeException(e);} 
            catch (IllegalAccessException e) {throw new RuntimeException(e);}
        }
        
        int cores = Runtime.getRuntime().availableProcessors();
        this.pool = Executors.newFixedThreadPool(cores);
        
        this.resources = new HashMap<String,Semaphore>();
        for (Map.Entry<String,Integer> entry : resources.entrySet()) {
            this.resources.put(entry.getKey(), new Semaphore(entry.getValue()));
        }
        if (!this.resources.containsKey("cpu")) {
            this.resources.put("cpu",new Semaphore(Runtime.getRuntime().availableProcessors()));
        }
        if (!this.resources.containsKey("microscope")) {
            this.resources.put("microscope",new Semaphore(1));
        }
        
        this.workflowInstance = this.workflowDb.table(WorkflowInstance.class);
        this.workflowDirectory = workflowDirectory;
        this.workflow = workflow;

        this.instance = instanceId == null? newWorkflowInstance() :
                        workflowInstance.selectOneOrDie(where("id",instanceId));
        this.instanceDb = Connection.get(
                new File(workflowDirectory, WORKFLOW_DB).getPath(),
                new File(this.workflowDirectory, 
                		new File(this.instance.getStorageLocation(), 
                				this.instance.getName()+".db").getPath()).getPath());
        this.moduleConfig = this.instanceDb.table(ModuleConfig.class);
        this.taskConfig = this.instanceDb.table(TaskConfig.class);
        this.taskStatus = this.instanceDb.table(Task.class);
        this.taskDispatch = this.instanceDb.table(TaskDispatch.class);
        
        this.logHandlers = new ArrayList<Handler>();
		try {
            logHandlers.add(new Logger.LogFileHandler(
            		new File(workflowDirectory,
            				new File(instance.getStorageLocation(), LOG_FILE).getPath()).getPath()));
		} 
		catch (SecurityException e) {throw new RuntimeException(e);} 
		catch (IOException e) {throw new RuntimeException(e);}

        this.taskListeners = new ArrayList<TaskListener>();
        this.mmslide = mmslide;
    }
    
    /**
     * Initialize the workflow instance's subdirectories and tasks.txt files.
     * @return the assigned instance_id for the new workflow instance.
     */
    private WorkflowInstance newWorkflowInstance() {
        WorkflowInstance instance = new WorkflowInstance();
        workflowInstance.insert(instance);
        // Create a new directory for the workflow instance.
        instance.createStorageLocation(this.workflowDirectory.getPath());
        workflowInstance.update(instance,"id");
        return instance;
    }
    
    public void deleteTaskRecords() {
        List<WorkflowModule> modules = workflow.select(where("parentId",null));
        for (WorkflowModule module : modules) {
            deleteTaskRecords(module);
        }
    }
    public void deleteTaskRecords(WorkflowModule module) {
        // Delete any child task/dispatch records
        List<WorkflowModule> childModules = workflow.select(where("parentId",module.getId()));
        for (WorkflowModule child : childModules) {
            deleteTaskRecords(child);
        }
        // Delete task dispatch records
        List<Task> tasks = taskStatus.select(where("moduleId",module.getId()));
        for (Task task : tasks) {
            taskDispatch.delete(where("taskId",task.getId()));
        }
        // Then delete task records
        taskStatus.delete(where("moduleId",module.getId()));
    }
    
    public void createTaskRecords() {
        List<WorkflowModule> modules = workflow.select(where("parentId",null));
        while (modules.size() > 0) {
            List<WorkflowModule> childModules = new ArrayList<WorkflowModule>();
            for (WorkflowModule module : modules) {
                Module m = moduleInstances.get(module.getId());
                if (m == null) {
                    throw new RuntimeException("No instantiated module found with ID: "+module.getId());
                }
                m.createTaskRecords();
                childModules.addAll(workflow.select(where("parentId",module.getId())));
            }
            modules = childModules;
        }
    }
    
    public Future<Status> run(final String startModuleId) {
        Future<Status> future = pool.submit(new Callable<Status>() {
            @Override
            public Status call() {
                Task start = taskStatus.selectOneOrDie(where("moduleId",startModuleId));
                Future<Status> future = run(start, null);
                try { return future.get(); }
                catch (InterruptedException e) {throw new RuntimeException(e);} 
                catch (ExecutionException e) {throw new RuntimeException(e);} 
            }
        });
        return future;
    }
    
    /**
     * Given a task, call all of its successors.
     * @param task
     * @param resume
     */
    private Future<Status> run(
            final Task task, 
            final Map<String,Integer> inheritedResources) 
    {
        final WorkflowModule module = this.workflow.selectOneOrDie(
                where("id",task.getModuleId()));
        
        // merge the task and module configuration
        List<Config> configs = new ArrayList<Config>();
        configs.addAll(taskConfig.select(where("id",task.getId())));
        configs.addAll(moduleConfig.select(where("id",task.getModuleId())));
        final Map<String,Config> config = Config.merge(configs);
        
        // make sure all required fields are filled in
        for (Map.Entry<String,Config> c : config.entrySet()) {
            if (c.getValue().isRequired() && c.getValue().getValue() == null) {
                throw new RuntimeException("Required value "+
                        c.getKey()+" missing for task "+task.getModuleId());
            }
        }
            
        final WorkflowRunner workflowRunner = this;
                
        Callable<Status> callable = new Callable<Status>() {
            @Override
            public Status call() {
                Status status = task.getStatus();
                
                // get an instance of the module
                Module taskModule = moduleInstances.get(module.getId());
                if (taskModule == null) {
                    throw new RuntimeException("No instantiated module found with ID: "+module.getId());
                }
                
                Map<String,Integer> acquiredResources = new HashMap<String,Integer>();
                try {
                    if (status == Status.NEW || status == Status.DEFER) {
                        // set the task status to IN_PROGRESS
                        task.setStatus(Status.IN_PROGRESS);
                        taskStatus.update(task, "id","moduleId");
                        
                        // instantiate a logger for the task
                        Logger taskLogger = Logger.create(
                        		new File(workflowDirectory,
                                    new File(task.getStorageLocation(), LOG_FILE).getPath()).getPath(),
                                task.getModuleId(),
                                logLevel);
                        for (Handler handler : logHandlers) {
                        	taskLogger.addHandler(handler);
                        }
                        
                        // figure out the required resources for this task
                        Map<String,Integer> requiredResources = new HashMap<String,Integer>();
                        requiredResources.putAll(taskModule.getResources());
                        if (!requiredResources.containsKey("cpu")) {
                            requiredResources.put("cpu", 1);
                        }
                        // acquire the required resources for this task
                        for (Map.Entry<String,Integer> resource : requiredResources.entrySet()) {
                            int requiredResource = resource.getValue();
                            if (inheritedResources != null && inheritedResources.containsKey(resource.getKey())) {
                                requiredResource -= Math.min(requiredResource, inheritedResources.get(resource.getKey()));
                            }
                            if (requiredResource > 0) {
                                taskLogger.info("Acquiring resource "+resource.getKey()+" ("+resource.getValue()+")");
                                resources.get(resource.getKey()).acquireUninterruptibly(requiredResource);
                                acquiredResources.put(resource.getKey(), requiredResource);
                                taskLogger.info("Acquired resource "+resource.getKey()+" ("+resource.getValue()+")");
                            }
                        }
                        
                        // run the task
                        taskLogger.info("Running module "+module.getId()+", task ID "+task.getId());
                        try {
                            status = taskModule.run(task, config, taskLogger);
                        } 
                        // Uncaught exceptions set the status to ERROR
                        catch (Exception e) {
                            taskLogger.severe(String.format("Error reported during task %s:%n%s", 
                                    task.toString(), e.toString()));
                            status = Status.ERROR;
                        }
                        taskLogger.info("Finished module "+module.getId()+", task ID "+task.getId());
                    }
                    
                    // This section must be synchronized both in java and in the backend database 
                    // to avoid any race conditions.
                    List<Future<Status>> childFutures = new ArrayList<Future<Status>>();
                    synchronized (workflowRunner) {
                        DatabaseConnection db = instanceDb.getReadWriteConnection();
                        instanceDb.saveSpecialConnection(db);
                        Savepoint savePoint = null;
                        try {
                            // explicitly lock the task status and dispatch tables to avoid deadlock exceptions
                            // See: http://hsqldb.org/doc/guide/sessions-chapt.html
                            db.setAutoCommit(false);
                            savePoint = db.setSavePoint("task");
                            db.executeStatement("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE", 
                                    DatabaseConnection.DEFAULT_RESULT_FLAGS);
                            db.executeStatement("LOCK TABLE TASK WRITE, TASKDISPATCH WRITE", 
                                    DatabaseConnection.DEFAULT_RESULT_FLAGS);
                            
                            // update the task status
                            task.setStatus(status);
                            taskStatus.update(task, "id","moduleId");
        
                            // enqueue the child tasks
                            if (status == Status.SUCCESS) {
                                List<TaskDispatch> childTaskIds = taskDispatch.select(
                                        where("parentTaskId",task.getId()));
                                            
                                CHILD_TASK:
                                for (TaskDispatch childTaskId : childTaskIds) {
                                    Task childTask = taskStatus.selectOneOrDie(
                                            where("id",childTaskId.getTaskId()));
                                    
                                    // do not run the child task unless all of its parent tasks 
                                    // have been successfully completed
                                    List<TaskDispatch> parentTaskIds = taskDispatch.select(
                                            where("taskId",childTaskId.getTaskId()));
                                    for (TaskDispatch parentTaskId : parentTaskIds) {
                                        Task parentTask = taskStatus.selectOneOrDie(
                                                where("id",parentTaskId.getParentTaskId()));
                                        if (parentTask.getStatus() != Status.SUCCESS) {
                                            continue CHILD_TASK;
                                        }
                                    }
                                    
                                    if (module.getTaskType() == TaskType.SERIAL) {
                                        // combine any inherited and acquired resources and pass them to the child task.
                                        Map<String,Integer> childResources = new HashMap<String,Integer>();
                                        if (inheritedResources != null) {
                                            childResources.putAll(inheritedResources);
                                        }
                                        for (Map.Entry<String,Integer> resource : acquiredResources.entrySet()) {
                                            Integer childResource = childResources.get(resource.getKey());
                                            childResources.put(resource.getKey(), 
                                                    resource.getValue() + (childResource != null? childResource : 0));
                                        }
                                        childFutures.add(run(childTask, childResources));
                                    }
                                    else if (module.getTaskType() == TaskType.PARALLEL) {
                                        childFutures.add(run(childTask, null));
                                    }
                                    else {
                                        throw new RuntimeException("Unknown task type: "+module.getTaskType());
                                    }
                                }
                            }
                            db.commit(savePoint);
                        }
                        catch (Exception e) {
                            if (savePoint != null) db.rollback(savePoint);
                            throw new RuntimeException(e);
                        }
                        finally {
                           instanceDb.clearSpecialConnection(db);
                        }
                    }

                    // notify any task listeners
                    for (TaskListener listener : taskListeners) {
                        listener.notifyTask(task);
                    }
                    
                    // resolve all the futures into statuses
                    List<Status> statuses = new ArrayList<Status>();
                    statuses.add(status);
                    for (Future<Status> f : childFutures) {
                        try {
                            Status s = f.get();
                            statuses.add(s);
                        } 
                        catch (ExecutionException e) {throw new RuntimeException(e);}
                        catch (InterruptedException e) {throw new RuntimeException(e);}
                    }
                    // return the coalesced status
                    return coalesceStatuses(statuses);
                } 
                catch (SQLException e) {throw new RuntimeException(e);}
                finally {
                    // relinquish any acquired resources
                    for (Map.Entry<String,Integer> resource : acquiredResources.entrySet()) {
                        resources.get(resource.getKey()).release(resource.getValue());
                    }
                }
            }
        };
                
        Future<Status> future;
        // Serial tasks get run immediately
        if (module.getTaskType() == TaskType.SERIAL) {
            FutureTask<Status> futureTask = new FutureTask<Status>(callable);
            futureTask.run();
            future = futureTask;
        }
        // Parallel tasks get put in the task pool
        else if (module.getTaskType() == TaskType.PARALLEL) {
            future = pool.submit(callable);
        }
        else {
            throw new RuntimeException("Unknown task type: "+module.getTaskType());
        }
        return future;
    }
    
    /**
     * Sort the child statuses and set this status to 
     * the highest priority status.
     */
    private static Status coalesceStatuses(List<Status> statuses) {
        if (statuses.size() > 0) {
            Collections.sort(statuses);
            return statuses.get(0);
        }
        return Status.SUCCESS;
    }
    
    /** 
     * Stop any new tasks from getting queued up.
     */
    public void stop() {
        pool.shutdown();
    }

    /** 
     * Stop all actively executing tasks and stop processing any waiting tasks.
     */
    public List<Runnable> kill() {
        return pool.shutdownNow();
    }
    
    /**
     * Get a list of the instance ids from the workflow instance file inside
     * a workflow directory.
     * @param workflowDirectory
     * @return
     */
    public static List<Integer> getInstanceIds(String workflowDirectory) {
        List<Integer> instanceIds = new ArrayList<Integer>();
        Connection workflowDb = Connection.get(new File(workflowDirectory, WORKFLOW_DB).getPath());
        if (workflowDb != null) {
            Dao<WorkflowInstance> workflowInstance = workflowDb.table(WorkflowInstance.class);
            for (WorkflowInstance instance : workflowInstance.select()) {
                instanceIds.add(instance.getId());
            }
            Collections.sort(instanceIds);
        }
        return instanceIds;
    }
    
    // Various getters/setters
    public File getWorkflowDirectory() { return workflowDirectory; }
    public Dao<WorkflowModule> getWorkflow() { return workflow; }
    public Dao<ModuleConfig> getModuleConfig() { return moduleConfig; }
    public Dao<WorkflowInstance> getWorkflowInstance() { return workflowInstance; }
    public Level getLogLevel() { return logLevel; }
    public void setLogLevel(Level logLevel) { this.logLevel = logLevel; }
    public int getMaxThreads() { return maxThreads; }
    public WorkflowInstance getInstance() { return instance; }
    public Dao<Task> getTaskStatus() { return taskStatus; }
    public Dao<TaskDispatch> getTaskDispatch() { return taskDispatch; }
    public Dao<TaskConfig> getTaskConfig() { return taskConfig; }
    public Connection getWorkflowDb() { return workflowDb; }
    public Connection getInstanceDb() { return instanceDb; }
    public MMSlide getMMSlide() { return mmslide; }
    
    public void addTaskListener(TaskListener listener) {
        taskListeners.add(listener);
    }
    public void removeTaskListener(TaskListener listener) {
        taskListeners.remove(listener);
    }
    public void addLogHandler(Handler handler) {
    	logHandlers.add(handler);
    }
    public boolean removeLogHandler(Handler handler) {
    	return logHandlers.remove(handler);
    }
}
