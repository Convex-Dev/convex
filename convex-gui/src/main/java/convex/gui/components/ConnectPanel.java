package convex.gui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.net.ConnectException;
import java.net.InetSocketAddress;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.util.Utils;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ConnectPanel extends JPanel {

	private HostCombo hostField;
	private JTextField addressField;
	private JTextField keyField;

	public ConnectPanel() {
		ConnectPanel pan=this;
		pan.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		pan.setLayout(new MigLayout("fill,wrap 2","","[fill]10[fill]"));
		pan.add(new JLabel("Peer:"));
		hostField=new HostCombo();
		pan.add(hostField);
		
		pan.add(new JLabel("Address:"));
		addressField=new JTextField("#12");
		pan.add(addressField);

		pan.add(new JLabel("Private Key:   "));
		keyField=new JTextField("");
		keyField.setMinimumSize(new Dimension(200,25));
		pan.add(keyField);
	}


	public static Convex tryConnect(JComponent parent) {
		return tryConnect(parent,"Enter Connection Details");
	}
	
	public static Convex tryConnect(JComponent parent,String prompt) {
		ConnectPanel pan=new ConnectPanel();
		
		int result = JOptionPane.showConfirmDialog(parent, pan, 
	               prompt, JOptionPane.OK_CANCEL_OPTION);

		if (result == JOptionPane.OK_OPTION) {
	    	try {
	    		String target=pan.hostField.getText();
	    		InetSocketAddress sa=Utils.toInetSocketAddress(target);
	    		System.err.println("MainGUI attemptiong connect to: "+sa);
	    		Convex convex=Convex.connect(sa);
	    		convex.setAddress(Address.parse(pan.addressField.getText()));
	    		
	    		HostCombo.registerGoodConnection(target);
	    		
	    		Blob b=Blob.parse(pan.keyField.getText());
	    		if ((b!=null)&&(!b.isEmpty())) {
	    			AKeyPair kp=AKeyPair.create(b);
	    			convex.setKeyPair(kp);
	    		}
	    		return convex;
	    	} catch (ConnectException e) {
	    		Toast.display(parent, "Connection Refused! "+e.getMessage(), Color.RED);
	    	} catch (Exception e) {
	    		Toast.display(parent, "Connect Failed: "+e.getMessage(), Color.RED);
	    		e.printStackTrace();
	    	}
	    }
		return null;
	}
}
