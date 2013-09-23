package org.bdgp.MMSlide.Modules;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.bdgp.MMSlide.Connection;
import org.bdgp.MMSlide.Dao;
import org.bdgp.MMSlide.MMSlide;
import org.bdgp.MMSlide.DB.Pool;
import org.bdgp.MMSlide.DB.PoolSlide;
import org.bdgp.MMSlide.DB.Slide;
import org.micromanager.PositionListDlg;
import org.micromanager.api.ScriptInterface;

import javax.swing.JList;

import net.miginfocom.swing.MigLayout;
import static org.bdgp.MMSlide.StorableConfiguration.Storable;

import javax.swing.JTextPane;

import java.awt.Font;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import javax.swing.JLabel;
import javax.swing.JTextArea;

import static org.bdgp.MMSlide.Util.where;
import javax.swing.SwingConstants;

@SuppressWarnings("serial")
public class SlideLoaderDialog extends JPanel {
    final SlideLoaderDialog self = this;

	@Storable JList<String> poolList;
	@Storable JRadioButton radioButtonSlideLoader;
	@Storable JRadioButton radioButtonSlideManual;
	
	public SlideLoaderDialog(final Connection connection, final MMSlide mmslide) {
		JTabbedPane tabbedPanel = new JTabbedPane();
		getRootPane().add(tabbedPanel);
		
        final Dao<Slide> slideDao = connection.table(Slide.class);
        final Dao<PoolSlide> poolSlideDao = connection.table(PoolSlide.class);

		// Pool (define new pool or select previous one)
		JPanel poolPanel = new JPanel();
		tabbedPanel.addTab("Pool", null, poolPanel, null);
		poolPanel.setLayout(new MigLayout("", "[256px,grow][1px]", "[64px,center][][grow][]"));
		
		JPanel panelSelectPool = new JPanel();
		poolPanel.add(panelSelectPool, "cell 0 0,alignx left,aligny top");
		panelSelectPool.setLayout(new MigLayout("", "[77px][114px]", "[22px][]"));
		
		// Populate the previous pool list from the Pool table
		Dao<Pool> poolDao = connection.table(Pool.class);
		final List<Pool> pools = poolDao.select();
		List<String> pool_names = new ArrayList<String>();
		for (Pool p : pools) {
		    pool_names.add(p.getName());
		}
		Collections.sort(pool_names);
		
		JLabel lblSelectPool = new JLabel("Select Pool:");
		panelSelectPool.add(lblSelectPool, "cell 0 1");
		
		JScrollPane scrollPane_prevPool = new JScrollPane();
		panelSelectPool.add(scrollPane_prevPool, "cell 1 1");
		
		poolList = new JList<String>();
		poolList.setListData(pool_names.toArray(new String[0]));
		poolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		poolList.setLayoutOrientation(JList.VERTICAL);
		poolList.setVisibleRowCount(-1);
		
		scrollPane_prevPool.setViewportView(poolList);
		poolList.setEnabled(false);
		
		JScrollPane poolPane = new JScrollPane();
		poolPanel.add(poolPane, "cell 0 2,grow");
		
		JTextPane poolDescriptionHeader = new JTextPane();
		poolDescriptionHeader.setText("Cartridge Position\tSlide Position\tExperiment ID");
		poolDescriptionHeader.setEditable(false);
		poolDescriptionHeader.setFont(new Font(
		        poolDescriptionHeader.getFont().getName(), 
		        Font.BOLD, 
		        poolDescriptionHeader.getFont().getSize()));
		poolPane.setColumnHeaderView(poolDescriptionHeader);
		
		final JTextArea poolDescription = new JTextArea();
		poolPane.setViewportView(poolDescription);
		
		JPanel panel = new JPanel();
		poolPanel.add(panel, "cell 0 1,grow");
		panel.setLayout(new MigLayout("", "[]", "[]"));
		
		JTextPane poolInstructions = new JTextPane();
		poolInstructions.setText("Create New Pool:\n\nText must be whitespace-separated with three columns: Cartridge Position, Slide Position, Experiment ID. The Cartride Position and Slide Position must be non-negative integers, the Experiment ID can be any text string.\n\nYou may want to simply copy-paste from your favorite spreadsheet editor.");
		poolInstructions.setEditable(false);
		panel.add(poolInstructions, "cell 0 0,grow");
		
		JButton btnCreateNewPool = new JButton("Create New Pool");
		btnCreateNewPool.addActionListener(new ActionListener() {
		    public void actionPerformed(ActionEvent e) {
		        BufferedReader poolReader = new BufferedReader(new StringReader(poolDescription.getText()));
	            Set<PoolSlide> poolSlides = new HashSet<PoolSlide>();
	            List<Slide> slides = new ArrayList<Slide>();
		        try {
    		        String line;
    		        int linenum=1;
                    while ((line = poolReader.readLine()) != null) {
                        // skip blank lines
                        if (line.matches("^\\s*$")) continue;

                        String[] fields = line.split("\t");
                        if (fields.length < 3) {
                            JOptionPane.showMessageDialog(self, "Line "+linenum+": Only "+fields.length+" fields found (should be 3)", 
                                    "Pool Description Formatting Error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        if (!fields[0].matches("^[0-9]$")) {
                            JOptionPane.showMessageDialog(self, "Line "+linenum+": Cartridge Position \""+fields[0]+"\" is invalid",
                                    "Pool Description Formatting Error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        if (!fields[1].matches("^[0-9]$")) {
                            JOptionPane.showMessageDialog(self, "Line "+linenum+": Slide Position \""+fields[1]+"\" is invalid",
                                    "Pool Description Formatting Error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        Slide slide = new Slide(fields[2]);
                        slides.add(slide);
                        PoolSlide poolSlide = new PoolSlide( 0,
                                new Integer(fields[0]).intValue(), 
                                new Integer(fields[1]).intValue(), 
                                        slides.size());
                        if (poolSlides.contains(poolSlide)) {
                            JOptionPane.showMessageDialog(self, "Line "+linenum+": Slide Position "+fields[0]+","+fields[1]+"\" was already entered",
                                    "Pool Description Formatting Error", JOptionPane.ERROR_MESSAGE);
                            return;                          
                        }
                        linenum++;
                    }
                } 
		        catch (IOException e1) {throw new RuntimeException(e1);}
		        
		        // Create the pool record
	            Dao<Pool> poolDao = connection.table(Pool.class);
	            Pool pool = new Pool();
	            poolDao.insert(pool);

	            // Create the poolslide and slide records
		        for (PoolSlide poolSlide : poolSlides) {
		            Slide slide = slides.get(poolSlide.getSlideId());
		            slideDao.insert(slide);
		            poolSlideDao.insert(new PoolSlide(pool.getId(), 
		                    poolSlide.getCartridgePosition(), 
		                    poolSlide.getSlidePosition(), 
		                    slide.getId()));
		        }

    		    // Update the pool list and select the newly created pool
        		final List<Pool> pools = poolDao.select();
        		List<String> pool_names = new ArrayList<String>();
        		for (Pool p : pools) {
        		    pool_names.add(p.getName());
        		}
        		Collections.sort(pool_names);
        		poolList.setListData(pool_names.toArray(new String[0]));
        		poolList.setSelectedValue(pool.getName(), true);
		    }
		});
		poolPanel.add(btnCreateNewPool, "cell 0 3");
		// Populate the pool description if another pool ID was selected
		poolList.addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent e) {
			    String name = poolList.getSelectedValue();
			    for (Pool p : pools) {
			        if (p.getName().equals(name)) {
			            StringBuilder sb = new StringBuilder();
			            List<PoolSlide> poolSlides = poolSlideDao.select(where("id",p.getId()));
			            Collections.sort(poolSlides);
			            for (PoolSlide poolSlide : poolSlides) {
			                Slide slide = slideDao.selectOne(where("id",poolSlide.getSlideId()));
			                sb.append(String.format("%d\t%d\t%s%n", 
			                        poolSlide.getCartridgePosition(), 
			                        poolSlide.getSlidePosition(), 
			                        slide.getExperimentId()));
			            }
			            poolDescription.setText(sb.toString());
			            return;
			        }
			    }
			}
		});

		JPanel loaderPanel = new JPanel();
		tabbedPanel.addTab("Loading", null, loaderPanel, null);
		loaderPanel.setLayout(new MigLayout("", "[195px]", "[22px][22px]"));
		
		ButtonGroup slideLoaderGroup = new ButtonGroup();
		radioButtonSlideLoader = new JRadioButton("Slide loader");
		loaderPanel.add(radioButtonSlideLoader, "cell 0 0,alignx left,aligny center");
		slideLoaderGroup.add(radioButtonSlideLoader);
		
		radioButtonSlideManual = new JRadioButton("Manual (will prompt for slides)");
		radioButtonSlideManual.setSelected(true);
		loaderPanel.add(radioButtonSlideManual, "cell 0 1,alignx left,aligny center");
		slideLoaderGroup.add(radioButtonSlideManual);

		// Positions (what to image: entire slide, previously defined positions)
		JPanel posPanel = new JPanel();
		tabbedPanel.addTab("Positions", posPanel);
		posPanel.setLayout(new MigLayout("", "[595px]", "[347px]"));
		
		JButton btnDisplayXyPosition = new JButton("Display XY Position List Dialog");
		btnDisplayXyPosition.addActionListener(new ActionListener() {
		    public void actionPerformed(ActionEvent e) {
        		ScriptInterface script = mmslide.getApp();
        		PositionListDlg posList = script.getXYPosListDlg();
        		posList.setVisible(true);
		    }
		});
		posPanel.add(btnDisplayXyPosition, "cell 0 0,aligny top");
		
	}
}
