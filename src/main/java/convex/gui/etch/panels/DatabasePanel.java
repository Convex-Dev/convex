package convex.gui.etch.panels;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.awt.Color;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;

import convex.gui.components.ActionPanel;
import convex.gui.etch.EtchExplorer;
import etch.EtchStore;

import javax.swing.JButton;
import javax.swing.JFileChooser;

@SuppressWarnings("serial")
public class DatabasePanel extends JPanel {
	
	public static final Logger log = Logger.getLogger(DatabasePanel.class.getName());

	
	/**
	 * Create the panel.
	 */
	public DatabasePanel(EtchExplorer explorer) {
		setPreferredSize(new Dimension(800,600));
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new JPanel();
		add(panel);
		panel.setLayout(new GridLayout(0, 1, 0, 0));
		
		JPanel filePanel = new JPanel();
		filePanel.setBorder(new TitledBorder(new EtchedBorder(EtchedBorder.LOWERED, new Color(255, 255, 255), new Color(160, 160, 160)), "File", TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
		panel.add(filePanel);
		filePanel.setLayout(new BorderLayout(0, 0));
		
		JLabel lblSelectPrompt = new JLabel("Select an Etch Database to Explore");
		filePanel.add(lblSelectPrompt, BorderLayout.NORTH);
		
		JLabel lblDatabaseFile = new JLabel();
		filePanel.add(lblDatabaseFile);
		lblDatabaseFile.setText(explorer.getStore().getFileName());
		explorer.getEtchState().addPropertyChangeListener(pc->{
			lblDatabaseFile.setText(((EtchStore)pc.getNewValue()).getFileName());
		});
		
		
		JPanel actionPanel = new ActionPanel();
		filePanel.add(actionPanel, BorderLayout.SOUTH);
		
		JButton btnOpen = new JButton("Open File...");
		actionPanel.add(btnOpen);
		final JFileChooser fc = new JFileChooser();
		btnOpen.addActionListener(e->{
		    if (e.getSource() == btnOpen) {
		        fc.setCurrentDirectory(explorer.getStore().getFile());
		        int returnVal = fc.showOpenDialog(DatabasePanel.this);
		
		        if (returnVal == JFileChooser.APPROVE_OPTION) {
		            File file = fc.getSelectedFile();
		            log.log(Level.INFO,"Opening Etch Database: " + file.getName());
		            
		            if (file.exists()) {
		            	try {
		            		EtchStore newEtch=EtchStore.create(file);
		            		explorer.setStore(newEtch);
		            	} catch (IOException ex) {
				            log.log(Level.WARNING,"Error opening Etch database: " + ex.getMessage());
				        			            		
		            	}
		            }
		        } 
		    } 
		});
		

		JLabel lblWelome = new JLabel("Welcome to Convex");
		lblWelome.setFont(new Font("Monospaced", Font.PLAIN, 18));
		lblWelome.setHorizontalAlignment(SwingConstants.CENTER);
		panel.add(lblWelome);

	}

}
