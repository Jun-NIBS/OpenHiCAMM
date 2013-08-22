package org.bdgp.MMSlide;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JComboBox;

import org.bdgp.MMSlide.DB.Config;
import org.bdgp.MMSlide.DB.Task;
import org.bdgp.MMSlide.DB.WorkflowModule;
import org.bdgp.MMSlide.Modules.Interfaces.Configuration;
import org.bdgp.MMSlide.Modules.Interfaces.Module;

import net.miginfocom.swing.MigLayout;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.bdgp.MMSlide.Util.where;

@SuppressWarnings("serial")
public class WorkflowDialog extends JFrame {
    private JTextField workflowDir;
    private JFileChooser directoryChooser;
    private JComboBox<String> workflowInstance;
    private JComboBox<String> startModule;
    private JButton editWorkflowButton;
    private JButton startButton;
    private JButton resumeButton;
    private JLabel lblConfigure;
    private JButton btnConfigure;

    public WorkflowDialog() {
        final WorkflowDialog thisDialog = this;
        
        directoryChooser = new JFileChooser();
        directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        directoryChooser.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
            }});
        getContentPane().setLayout(new MigLayout("", "[][grow]", "[][][][][]"));
        
        JLabel lblChooseWorkflowDirectory = new JLabel("Workflow Directory");
        getContentPane().add(lblChooseWorkflowDirectory, "cell 0 0,alignx trailing");
        workflowDir = new JTextField();
        workflowDir.setEditable(false);
        workflowDir.setColumns(40);
        getContentPane().add(workflowDir, "flowx,cell 1 0,growx");
        JButton openWorkflowButton = new JButton("Choose");
        getContentPane().add(openWorkflowButton, "cell 1 0");
        
        JLabel lblChooseWorkflowInstance = new JLabel("Workflow Instance");
        getContentPane().add(lblChooseWorkflowInstance, "cell 0 1,alignx trailing");
        workflowInstance = new JComboBox<String>();
        workflowInstance.setEnabled(false);
        workflowInstance.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                }
            }});
        getContentPane().add(workflowInstance, "cell 1 1,alignx right");
        
        JLabel lblChooseStartTask = new JLabel("Start Task");
        getContentPane().add(lblChooseStartTask, "cell 0 2,alignx trailing");
        startModule = new JComboBox<String>();
        startModule.setEnabled(false);
        startModule.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                }
            }});
        getContentPane().add(startModule, "cell 1 2,alignx trailing");
        
        lblConfigure = new JLabel("Configure Modules");
        getContentPane().add(lblConfigure, "cell 0 3,alignx trailing");
        
        btnConfigure = new JButton("Configure...");
        btnConfigure.setEnabled(false);
        btnConfigure.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Connection connection = Connection.get(
                        new File(workflowDir.getText(), WorkflowRunner.WORKFLOW_DB).getPath());
                
                // get list of JPanels and load them with the configuration interfaces
                Map<String,Configuration> configurations = new LinkedHashMap<String,Configuration>();
                Dao<WorkflowModule> modules = connection.table(WorkflowModule.class, WorkflowRunner.WORKFLOW);
                List<WorkflowModule> ms = modules.select(where("id",startModule.getItemAt(startModule.getSelectedIndex())));
                
                while (ms.size() > 0) {
                    List<WorkflowModule> newms = new ArrayList<WorkflowModule>();
                    for (WorkflowModule m : ms) {
                        try {
                            Module module = m.getModule().newInstance();
                            configurations.put(m.getId(), module.configure(connection));
                        }
                        catch (InstantiationException e1) {throw new RuntimeException(e1);} 
                        catch (IllegalAccessException e1) {throw new RuntimeException(e1);}
                        
                        newms.addAll(modules.select(where("parentId",m.getId())));
                    }
                    ms = newms;
                }
                
                if (configurations.size() > 0) {
                    thisDialog.setVisible(false);
                    WorkflowConfigurationDialog config = new WorkflowConfigurationDialog(
                            thisDialog, configurations, connection.table(Config.class));
                    config.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    config.pack();
                    config.setVisible(true);
                    config.addWindowListener(new WindowListener() {
                        @Override public void windowOpened(WindowEvent e) { }
                        @Override public void windowClosing(WindowEvent e) { }
                        @Override public void windowClosed(WindowEvent e) { 
                            thisDialog.setVisible(true);
                        }
                        @Override public void windowIconified(WindowEvent e) { }
                        @Override public void windowDeiconified(WindowEvent e) { }
                        @Override public void windowActivated(WindowEvent e) { }
                        @Override
                        public void windowDeactivated(WindowEvent e) { }
                    });
                }
            }
        });
        getContentPane().add(btnConfigure, "cell 1 3,alignx trailing");
        startButton = new JButton("Start");
        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                start(false);
            }
        });
        startButton.setEnabled(false);
        getContentPane().add(startButton, "flowx,cell 1 4,alignx trailing");
        
        resumeButton = new JButton("Resume");
        resumeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                start(true);
            }
        });
        resumeButton.setEnabled(false);
        getContentPane().add(resumeButton, "flowx,cell 1 4, alignx trailing");
        
        editWorkflowButton = new JButton("Edit Workflow...");
        editWorkflowButton.setEnabled(false);
        getContentPane().add(editWorkflowButton, "cell 1 0");
        editWorkflowButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                thisDialog.setVisible(false);
                WorkflowDesignerDialog designer = new WorkflowDesignerDialog(thisDialog, new File(workflowDir.getText()));
                designer.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                designer.pack();
                designer.setVisible(true);
                designer.addWindowListener(new WindowListener() {
                    @Override public void windowOpened(WindowEvent e) { }
                    @Override public void windowClosing(WindowEvent e) { }
                    @Override public void windowClosed(WindowEvent e) { 
                        thisDialog.setVisible(true);
                        refresh();
                    }
                    @Override public void windowIconified(WindowEvent e) { }
                    @Override public void windowDeiconified(WindowEvent e) { }
                    @Override public void windowActivated(WindowEvent e) { }
                    @Override public void windowDeactivated(WindowEvent e) { }
                });
            }});
        
        openWorkflowButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (directoryChooser.showDialog(thisDialog,"Choose Workflow Directory") == JFileChooser.APPROVE_OPTION) {
                    workflowDir.setText(directoryChooser.getSelectedFile().getPath());
                    editWorkflowButton.setEnabled(true);
                    refresh();
                }
                else {
                    editWorkflowButton.setVisible(false);
                }
            }
        });
    }
    public void refresh() {
        Connection connection = Connection.get(
                new File(workflowDir.getText(), WorkflowRunner.WORKFLOW_DB).getPath());
        // get list of starting modules
        List<String> startModules = new ArrayList<String>();
        Dao<WorkflowModule> modules = connection.table(WorkflowModule.class, WorkflowRunner.WORKFLOW);
        for (WorkflowModule module : modules.select()) {
            if (module.getParentId() == null) {
                startModules.add(module.getId());
            }
        }
        if (startModules.size() > 0) {
            Collections.sort(startModules);
            startModule.setModel(new DefaultComboBoxModel<String>(startModules.toArray(new String[0])));
            startModule.setEnabled(true);
            
            // get the list of workflow instances
            List<String> workflowInstances = new ArrayList<String>();
            workflowInstances.add("-Create new Instance-");
            Dao<Task> workflowStatus = connection.table(Task.class, WorkflowRunner.WORKFLOW_INSTANCE);
            for (Task task : workflowStatus.select()) {
                workflowInstances.add(Integer.toString(task.getId()));
            }
            Collections.sort(workflowInstances);
            workflowInstance.setModel(new DefaultComboBoxModel<String>(workflowInstances.toArray(new String[0])));
            workflowInstance.setEnabled(true);
            
            btnConfigure.setEnabled(true);
            startButton.setEnabled(true);
        }
        
    }
    public void start(boolean resume) {
        final WorkflowDialog thisDialog = this;
        thisDialog.setVisible(false);
        Integer instanceId = Integer.parseInt(workflowInstance.getItemAt(workflowInstance.getSelectedIndex()));
        String startModuleId = startModule.getItemAt(startModule.getSelectedIndex());
                
        // TODO: add gui widget to set resume
        WorkflowRunnerDialog wrd = new WorkflowRunnerDialog(new File(workflowDir.getText()), instanceId, startModuleId, resume);
        wrd.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        wrd.pack();
        wrd.setVisible(true);
        wrd.addWindowListener(new WindowListener() {
            @Override public void windowOpened(WindowEvent e) { }
            @Override public void windowClosing(WindowEvent e) { }
            @Override public void windowClosed(WindowEvent e) { 
                thisDialog.setVisible(true);
            }
            @Override public void windowIconified(WindowEvent e) { }
            @Override public void windowDeiconified(WindowEvent e) { }
            @Override public void windowActivated(WindowEvent e) { }
            @Override public void windowDeactivated(WindowEvent e) { }
        });
    }
}
