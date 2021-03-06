package org.bdgp.OpenHiCAMM;

import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JDialog;

import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;

import org.bdgp.OpenHiCAMM.DB.Config;
import org.bdgp.OpenHiCAMM.DB.ModuleConfig;
import org.bdgp.OpenHiCAMM.DB.WorkflowModule;
import org.bdgp.OpenHiCAMM.Modules.Interfaces.Configuration;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

import javax.swing.event.ChangeListener;
import javax.swing.event.ChangeEvent;

import static org.bdgp.OpenHiCAMM.Util.where;

@SuppressWarnings("serial")
public class WorkflowConfigurationDialog extends JDialog {
	JDialog parent;
	Map<String,Configuration> configurations;
	Dao<WorkflowModule> wmDao;
	Dao<ModuleConfig> config;
	
    public WorkflowConfigurationDialog(
            JDialog parent, 
            Map<String,Configuration> configurations, 
            Dao<WorkflowModule> wmDao,
            Dao<ModuleConfig> config)
    {
	    //super(parent, "Module Configuration", Dialog.ModalityType.DOCUMENT_MODAL);
	    super(parent, "Module Configuration");
	    this.parent = parent;
	    this.configurations = configurations;
	    this.wmDao = wmDao;
	    this.config = config;

	    this.setPreferredSize(new Dimension(1024,768));
	    final WorkflowConfigurationDialog thisDialog = this;
        getContentPane().setLayout(new MigLayout("", "[grow][]", "[grow][]"));
        
        // make a workflowmodule name -> id lookup 
        Map<String,Integer> name2id = new HashMap<>();
        for (WorkflowModule wm : this.wmDao.select()) {
            name2id.put(wm.getName(), wm.getId());
        }
        
        final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        for (Map.Entry<String,Configuration> entry : configurations.entrySet()) {
            Integer moduleId = name2id.get(entry.getKey());
            List<ModuleConfig> configs = config.select(where("id",moduleId));
            
            Component panel = entry.getValue().display(configs.toArray(new Config[0]));
            if (panel != null) {
                tabbedPane.add(entry.getKey(), panel);
            }
        }
        getContentPane().add(tabbedPane, "cell 0 0 2 1,grow");
        
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                thisDialog.dispose();
            }
        });
        getContentPane().add(btnCancel, "cell 0 1");
        
        final JButton btnPrevious = new JButton("< Previous");
        final JButton btnNext = new JButton("Next >");
        
        btnPrevious.setEnabled(tabbedPane.getSelectedIndex() > 0);
        btnNext.setEnabled(tabbedPane.getSelectedIndex() < tabbedPane.getTabCount()-1);
        
        tabbedPane.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                btnPrevious.setEnabled(tabbedPane.getSelectedIndex() > 0);
                btnNext.setEnabled(tabbedPane.getSelectedIndex() < tabbedPane.getTabCount()-1);
            }
        });
        
        btnPrevious.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (tabbedPane.getSelectedIndex() > 0) {
                    tabbedPane.setSelectedIndex(tabbedPane.getSelectedIndex()-1);
                }
                btnPrevious.setEnabled(tabbedPane.getSelectedIndex() > 0);
                btnNext.setEnabled(tabbedPane.getSelectedIndex() < tabbedPane.getTabCount()-1);
            }
        });
        getContentPane().add(btnPrevious, "flowx,cell 1 1");
        
        btnNext.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (tabbedPane.getSelectedIndex() < tabbedPane.getTabCount()-1) {
                    tabbedPane.setSelectedIndex(tabbedPane.getSelectedIndex()+1);
                }
                btnPrevious.setEnabled(tabbedPane.getSelectedIndex() > 0);
                btnNext.setEnabled(tabbedPane.getSelectedIndex() < tabbedPane.getTabCount()-1);
            }
        });
        getContentPane().add(btnNext, "cell 1 1,alignx right");
        
        JButton btnFinish = new JButton("Finish");
        btnFinish.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
            	if (validateConfiguration()) {
            		storeConfiguration();
                    thisDialog.dispose();
            	}
            }
        });
        getContentPane().add(btnFinish, "cell 1 1");
    }
    
    public boolean validateConfiguration() {
    	List<ValidationError> errors = new ArrayList<ValidationError>();
    	for (Map.Entry<String,Configuration> entry : configurations.entrySet()) {
    		ValidationError[] error = entry.getValue().validate();
    		if (error != null) {
    			errors.addAll(Arrays.asList(error));
    		}
    	}
    	if (errors.size() > 0) {
    		StringBuilder errorMessage = new StringBuilder();
    		errorMessage.append(String.format("Please fix the following configuration errors:%n%n"));
    		for (ValidationError error : errors) {
    			errorMessage.append(String.format("%s: %s%n%n", error.getModuleId(), error.getMessage()));
    		}
    		JOptionPane.showMessageDialog(parent, errorMessage.toString(), "Configuration Errors", JOptionPane.ERROR_MESSAGE);
    		return false;
    	}
        return true;
    }
    
    public void storeConfiguration() {
        for (Map.Entry<String,Configuration> entry : configurations.entrySet()) {
            Config[] configs = entry.getValue().retrieve();
            if (configs != null) {
                WorkflowModule wm = wmDao.selectOneOrDie(where("name",entry.getKey()));
                for (Config c : configs) {
                    ModuleConfig setId = new ModuleConfig(wm.getId(), c.getKey(), c.getValue());
                    config.insertOrUpdate(setId,"id","key");
                }
            }
        }
    }
}
