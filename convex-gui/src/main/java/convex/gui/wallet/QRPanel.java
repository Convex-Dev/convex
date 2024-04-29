package convex.gui.wallet;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.components.QRCodePanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class QRPanel extends JPanel {

	public QRPanel(Convex convex) {
		setLayout(new MigLayout());
		
		add(new JLabel());

		add(new QRCodePanel("Test QR code with a reasonable length string to see what happens",300));
		
	}
}
