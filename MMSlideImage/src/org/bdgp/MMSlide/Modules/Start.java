package org.bdgp.MMSlide.Modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;

import org.bdgp.MMSlide.Logger;
import org.bdgp.MMSlide.WorkflowRunner;
import org.bdgp.MMSlide.DB.Config;
import org.bdgp.MMSlide.DB.Task;
import org.bdgp.MMSlide.DB.Task.Status;
import org.bdgp.MMSlide.Modules.Interfaces.Configuration;
import org.bdgp.MMSlide.Modules.Interfaces.Module;

import static org.bdgp.MMSlide.Util.map;

public class Start implements Module {
    @Override
    public Status run(WorkflowRunner workflow, Task task, Map<String,Config> config, Logger logger) {
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
            public List<Config> retrieve() {
                return new ArrayList<Config>();
            }
            @Override
            public JPanel display() {
                return new JPanel();
            }};
    }

    @Override
    public void createTaskRecords(WorkflowRunner workflow, String moduleId) {
        Task task = new Task(moduleId, workflow.getInstance().getStorageLocation(), Status.NEW);
        workflow.getTaskStatus().insert(task);
        task.update(workflow.getTaskStatus());
    }

    @Override
    public Map<String, Integer> getResources() {
        return map("cpu",1);
    }

}
