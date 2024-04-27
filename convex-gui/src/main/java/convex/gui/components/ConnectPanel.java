package convex.gui.components;

import java.awt.Color;
import java.net.ConnectException;
import java.net.InetSocketAddress;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.init.Init;
import convex.core.util.Utils;
import convex.gui.components.account.AddressCombo;
import convex.gui.keys.KeyRingPanel;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ConnectPanel extends JPanel {

	private HostCombo hostField;
	private AddressCombo addressField;

	public ConnectPanel() {
		ConnectPanel pan=this;
		pan.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		pan.setLayout(new MigLayout("fill,wrap 2","","[fill]10[fill]"));
		pan.add(new JLabel("Peer:"));
		hostField=new HostCombo();
		pan.add(hostField);
		
		pan.add(new JLabel("Address:"));
		addressField=new AddressCombo(Init.GENESIS_ADDRESS);
		pan.add(addressField);

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
	    		convex.setAddress(pan.addressField.getAddress());
	    		
	    		HostCombo.registerGoodConnection(target);
	    		
	    		AWalletEntry we=KeyRingPanel.findWalletEntry(convex);
	    		if ((we!=null)&&!we.isLocked()) {
	    			convex.setKeyPair(we.getKeyPair());
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
