package convex.gui.panels;

import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import convex.gui.components.WorldPanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class HomePanel extends JPanel {

	/**
	 * Create the panel.
	 */
	public HomePanel() {
		// setPreferredSize(new Dimension(800,600));
		setLayout(new MigLayout());

		JLabel lblWelome = new JLabel("Welcome to Convex");
		lblWelome.setFont(new Font("Monospaced", Font.PLAIN, 24));
		lblWelome.setHorizontalAlignment(SwingConstants.CENTER);
		// add(lblWelome,"dock north");

		add(new WorldPanel(),"dock center");
	}

}
