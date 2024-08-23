package convex.gui.etch;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.etch.EtchStore;
import convex.gui.components.ActionPanel;

@SuppressWarnings("serial")
public class DatabasePanel extends JPanel {
	
	public static final Logger log = LoggerFactory.getLogger(DatabasePanel.class.getName());

	
	/**
	 * Create the panel.
	 * @param explorer EtchExplorer instance
	 */
	public DatabasePanel(EtchExplorer explorer) {
		setPreferredSize(new Dimension(800,600));
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new JPanel();
		add(panel);
		panel.setLayout(new GridLayout(0, 1, 0, 0));
		
		JPanel filePanel = new JPanel();
		//Border eb=new EtchedBorder(EtchedBorder.LOWERED, Color.WHITE, new Color(160, 160, 160));
		//Border b = new TitledBorder(eb, "File", TitledBorder.LEADING, TitledBorder.TOP, (Font)null, Color.BLACK);
		//filePanel.setBorder(b);
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
		            log.info("Opening Etch Database: {}", file.getName());
		            
		            if (file.exists()) {
		            	try {
		            		EtchStore newEtch=EtchStore.create(file);
		            		explorer.setStore(newEtch);
		            	} catch (IOException ex) {
				            log.error("Error opening Etch database: " + ex.getMessage());
				        			            		
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
