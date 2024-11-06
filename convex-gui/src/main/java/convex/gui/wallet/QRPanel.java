package convex.gui.wallet;

import java.util.HashMap;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.components.QRCode;
import convex.gui.utils.Toolkit;
import convex.java.JSON;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class QRPanel extends JPanel {

	QRCode qrCode;
	
	public QRPanel(Convex convex) {
		setLayout(new MigLayout("wrap 1, fillx"));
		
		add(new JLabel());
		add(Toolkit.makeNote("Use this QR code to receive payments and share your account details with others. It is safe to share publicly, and only refers to information that you have already made public on the Convex network.")
				,"grow");

		HashMap<String,Object> rec=new HashMap<>();
		rec.put("address", convex.getAddress().longValue());
		String data=JSON.toPrettyString(rec);
		
		qrCode=new QRCode(data,400);
		add(qrCode,"align center");
	}
}
