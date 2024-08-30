package convex.gui.components;

import java.awt.Color;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.init.Init;
import convex.core.util.Utils;
import convex.gui.components.account.AddressCombo;
import convex.gui.keys.KeyRingPanel;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Panel for connecting to a Convex peer as a client wallet
 */
@SuppressWarnings("serial")
public class ConnectPanel extends JPanel {
	
	private static final Logger log = LoggerFactory.getLogger(ConnectPanel.class.getName());


	private HostCombo hostField;
	private AddressCombo addressField;

	public ConnectPanel() {
		ConnectPanel pan=this;
		pan.setLayout(new MigLayout("fill,wrap 3",""));
		
		{	// Peer selection
			pan.add(new JLabel("Peer"));
			hostField=new HostCombo();
			hostField.setToolTipText("Connect to a Convex peer that you trust. The peer is resposible for handling transactions on your behalf.");
			pan.add(hostField,"width 50:250:");
			pan.add(Toolkit.makeHelp(hostField.getToolTipText()));
		}
		
		{	// Address selection
			pan.add(new JLabel("Address"));
			addressField=new AddressCombo(Init.GENESIS_ADDRESS);
			addressField.setToolTipText("Set the initial account address to use. This should be an account for which you possess the private key.");
			pan.add(addressField,"width 50:250:");
			pan.add(Toolkit.makeHelp(addressField.getToolTipText()));
		}

	}

	public static Convex tryConnect(JComponent parent) {
		return tryConnect(parent,"Enter Connection Details");
	}
	
	public static Convex tryConnect(JComponent parent,String prompt) {
		ConnectPanel pan=new ConnectPanel();
		
		int result = JOptionPane.showConfirmDialog(parent, pan, 
	               prompt, JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE,SymbolIcon.get(0xea77,Toolkit.ICON_SIZE));

		if (result == JOptionPane.OK_OPTION) {
	    	try {
	    		String target=pan.hostField.getText();
	    		InetSocketAddress sa=Utils.toInetSocketAddress(target);
	    		log.info("Attempting connect to: "+sa);
	    		Convex convex=Convex.connect(sa);
	    		convex.setAddress(pan.addressField.getAddress());
	    		
	    		HostCombo.registerGoodConnection(target);
	    		
	    		AWalletEntry we=KeyRingPanel.findWalletEntry(convex);
	    		if ((we!=null)&&!we.isLocked()) {
	    			convex.setKeyPair(we.getKeyPair());
	    		}
	    		return convex;
	    	} catch (ConnectException e) {
				log.info("Failed to connect");
	    		Toast.display(parent, e.getMessage(), Color.RED);
	    	} catch (TimeoutException | IOException e) {
	    		Toast.display(parent, e.getMessage(), Color.RED);
	    		e.printStackTrace();
	    	}
	    } else {
	    	log.info("Connect cancelled by user");
	    }
		return null;
	}
}
