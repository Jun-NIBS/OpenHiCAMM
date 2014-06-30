package org.bdgp.MMSlide.Modules;

import javax.swing.JTabbedPane;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JButton;

import org.micromanager.AcqControlDlg;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

@SuppressWarnings("serial")
public class SlideImagerDialog extends JTabbedPane {
	JTextField acqSettingsText;
	JTextField posListText;
	JTextField posListName;
	public SlideImagerDialog(final AcqControlDlg acqControlDlg) {
		JPanel panel = new JPanel();
		addTab("New tab", null, panel, null);
		panel.setLayout(new MigLayout("", "[grow]", "[][][][][][][]"));
		
		JButton btnShowAcquisitionDialog = new JButton("Show Acquisition Dialog");
		if (acqControlDlg == null) {
            btnShowAcquisitionDialog.setEnabled(false);
        }
		btnShowAcquisitionDialog.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (acqControlDlg != null) {
                    acqControlDlg.setVisible(true);
                    acqControlDlg.toFront();
                    acqControlDlg.repaint();
				}
			}
		});
		panel.add(btnShowAcquisitionDialog, "cell 0 0");
		
		JLabel lblSelectMultid = new JLabel("Load Acqusition Settings File");
		panel.add(lblSelectMultid, "cell 0 1");
		
		acqSettingsText = new JTextField();
		acqSettingsText.setEditable(false);
		panel.add(acqSettingsText, "flowx,cell 0 2,growx");
		acqSettingsText.setColumns(10);
		
		JLabel lblLoad = new JLabel("Load Position List From File");
		panel.add(lblLoad, "cell 0 3");
		
        final JFileChooser acqSettingsChooser = new JFileChooser();
        acqSettingsChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		JButton btnLoadAcqSettings = new JButton("Select File");
		btnLoadAcqSettings.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
                if (acqSettingsChooser.showDialog(SlideImagerDialog.this,"Load Acquisition Settings File") == JFileChooser.APPROVE_OPTION) {
                    acqSettingsText.setText(acqSettingsChooser.getSelectedFile().getPath());
                }
			}
		});
		panel.add(btnLoadAcqSettings, "cell 0 2");
		
		posListText = new JTextField();
		posListText.setEditable(false);
		panel.add(posListText, "flowx,cell 0 4,growx");
		posListText.setColumns(10);
		
		final JFileChooser posListChooser = new JFileChooser();
		posListChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		JButton btnLoadPosList = new JButton("Select File");
		btnLoadPosList.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
                if (posListChooser.showDialog(SlideImagerDialog.this,"Load Position List File") == JFileChooser.APPROVE_OPTION) {
                    posListText.setText(posListChooser.getSelectedFile().getPath());
                }
			}
		});
		panel.add(btnLoadPosList, "cell 0 4");
		
		JLabel lblPositionListDb = new JLabel("Or Enter Position List DB Name");
		panel.add(lblPositionListDb, "cell 0 5");
		
		posListName = new JTextField();
		panel.add(posListName, "cell 0 6,growx");
		posListName.setColumns(10);
	}
}