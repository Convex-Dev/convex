package convex.gui.etch.panels;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Color;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;

import convex.gui.etch.EtchExplorer;

@SuppressWarnings("serial")
public class DatabasePanel extends JPanel {

	
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

		JLabel lblWelome = new JLabel("Welcome to Convex");
		lblWelome.setFont(new Font("Monospaced", Font.PLAIN, 18));
		lblWelome.setHorizontalAlignment(SwingConstants.CENTER);
		panel.add(lblWelome);

	}

}
