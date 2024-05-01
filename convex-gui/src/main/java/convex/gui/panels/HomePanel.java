package convex.gui.panels;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import convex.gui.components.WorldPanel;

import java.awt.Font;

@SuppressWarnings("serial")
public class HomePanel extends JPanel {

	/**
	 * Create the panel.
	 */
	public HomePanel() {
		setPreferredSize(new Dimension(800,600));
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new JPanel();
		add(panel);
		panel.setLayout(new BorderLayout(0, 0));

		JLabel lblWelome = new JLabel("Welcome to Convex");
		lblWelome.setFont(new Font("Monospaced", Font.PLAIN, 24));
		lblWelome.setHorizontalAlignment(SwingConstants.CENTER);
		panel.add(lblWelome, BorderLayout.NORTH);

		panel.add(new WorldPanel(), BorderLayout.CENTER);
	}

}
