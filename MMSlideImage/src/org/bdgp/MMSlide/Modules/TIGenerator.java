package org.bdgp.MMSlide.Modules;

import java.util.List;
import java.util.Map;

import javax.swing.JPanel;

import org.bdgp.MMSlide.Logger;
import org.bdgp.MMSlide.Util;
import org.bdgp.MMSlide.ValidationError;
import org.bdgp.MMSlide.WorkflowRunner;
import org.bdgp.MMSlide.DB.Config;
import org.bdgp.MMSlide.DB.Task;
import org.bdgp.MMSlide.DB.Task.Status;
import org.bdgp.MMSlide.DB.TaskDispatch;
import org.bdgp.MMSlide.DB.WorkflowModule;
import org.bdgp.MMSlide.Modules.Interfaces.Configuration;
import org.bdgp.MMSlide.Modules.Interfaces.Module;

import static org.bdgp.MMSlide.Util.map;
import static org.bdgp.MMSlide.Util.where;

public class TIGenerator implements Module {
    WorkflowRunner workflow;
    String moduleId;

    @Override
    public void initialize(WorkflowRunner workflow, String moduleId) {
        this.workflow = workflow;
        this.moduleId = moduleId;
    }

    @Override
    public Status run(Task task, Map<String,Config> config, Logger logger) {
        Util.sleep();
        return Status.SUCCESS;
    }

    @Override
    public String getTitle() {
        return this.getClass().getName();
    }

    @Override
    public String getDescription() {
        return this.getClass().getName();
    }

    @Override
    public Configuration configure() {
        return new Configuration() {
            @Override
            public Config[] retrieve() {
                return new Config[0];
            }
            @Override
            public JPanel display(Config[] configs) {
                return new JPanel();
            }
            @Override
            public ValidationError[] validate() {
                return null;
            }};
    }

    @Override
    public void createTaskRecords() {
        WorkflowModule module = workflow.getWorkflow().selectOneOrDie(where("id",moduleId));
        if (module.getParentId() != null) {
            List<Task> parentTasks = workflow.getTaskStatus().select(where("moduleId",module.getParentId()));
            for (Task parentTask : parentTasks) {
                Task task = new Task(moduleId, Status.NEW);
                workflow.getTaskStatus().insert(task);
                task.createStorageLocation(parentTask.getStorageLocation(), workflow.getWorkflowDirectory().getPath());
                workflow.getTaskStatus().update(task,"id");
                
                TaskDispatch dispatch = new TaskDispatch(task.getId(), parentTask.getId());
                workflow.getTaskDispatch().insert(dispatch);
            }
        }
        else {
            Task task = new Task(moduleId, Status.NEW);
            workflow.getTaskStatus().insert(task);
            task.createStorageLocation(workflow.getInstance().getStorageLocation(), workflow.getWorkflowDirectory().getPath());
            workflow.getTaskStatus().update(task,"id");
        }
    }

    @Override
    public Map<String, Integer> getResources() {
        return map("cpu",1);
    }
}
