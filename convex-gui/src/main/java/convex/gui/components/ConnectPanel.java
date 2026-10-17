package convex.gui.components;

import java.awt.Component;
import java.awt.EventQueue;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.cvm.Address;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.gui.components.account.AddressCombo;
import convex.gui.utils.Async;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.net.IPUtils;
import net.miginfocom.swing.MigLayout;

/**
 * Panel for connecting to a Convex peer as a client wallet
 */
@SuppressWarnings("serial")
public class ConnectPanel extends JPanel {
	
	private static final Logger log = LoggerFactory.getLogger(ConnectPanel.class.getName());

	private static Address lastAddress=null;
	
	private HostCombo hostField;
	private AddressCombo addressField;

	public ConnectPanel() {
		ConnectPanel pan=this;
		pan.setLayout(new MigLayout("fill,wrap 3",""));
		
		{	// Peer selection
			pan.add(new JLabel("Peer"));
			hostField=new HostCombo();
			hostField.setToolTipText("Connect to a Convex peer that you trust. \nThe peer is resposible for handling transactions on your behalf.");
			pan.add(hostField,"width 50:250:");
			pan.add(Toolkit.makeHelp(hostField.getToolTipText()));
		}
		
		{	// Address selection
			pan.add(new JLabel("Address"));
			addressField=new AddressCombo();
			addressField.setSelectedItem(getSuggestedAddress());
			addressField.setToolTipText("Set the initial account address to use. \nNormally this should be an account for which you possess the private key. \nIf you don't have the private key, you can still view the account but cannot execute transactions.");
			pan.add(addressField,"width 50:250:");
			pan.add(Toolkit.makeHelp(addressField.getToolTipText()));
		}

	}

	private static Address getSuggestedAddress() {
		if (lastAddress!=null) return lastAddress;
		return Init.GENESIS_ADDRESS;
	}

	public static CompletableFuture<Convex> tryConnect(JComponent parent) {
		return tryConnect(parent,"Enter Connection Details");
	}

	/**
	 * Asks for connection details, then connects off the EDT. Safe to call from any thread.
	 * @param parent Parent component for the dialog
	 * @param prompt Dialog title
	 * @return Future for the connection, with null if cancelled or the connection failed (failures are shown)
	 */
	public static CompletableFuture<Convex> tryConnect(JComponent parent,String prompt) {
		if (!EventQueue.isDispatchThread()) {
			return CompletableFuture.supplyAsync(()->tryConnect(parent,prompt), Async.EDT).thenCompose(f->f);
		}
		ConnectPanel pan=new ConnectPanel();

		int result = JOptionPane.showConfirmDialog(parent, pan,
	               prompt, JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE,SymbolIcon.get(0xea77,Toolkit.ICON_SIZE));
		if (result != JOptionPane.OK_OPTION) {
	    	log.info("Connect cancelled by user");
	    	return CompletableFuture.completedFuture(null);
		}

		Address addr=pan.addressField.getAddress();
		lastAddress=addr;
		String target=pan.hostField.getText();
		return Async.background(()->{
			InetSocketAddress sa=IPUtils.toInetSocketAddress(target);
			if (sa==null) throw new IllegalArgumentException("Invalid peer address: "+target);
			log.info("Attempting connect to: "+sa);
			Convex convex=ConvexRemote.connectNetty(sa);
			convex.setAddress(addr);
			return convex;
		}).handleAsync((convex,e)->{
			if (e!=null) {
				Async.showFailure(parent, e);
				return null;
			}
			HostCombo.registerGoodConnection(target);
			// No key yet: the app using the connection finds its signer
			return convex;
		}, Async.EDT);
	}

	/**
	 * Shows diagnostic information for a connection, gathered off the EDT
	 * @param parent Parent component for the dialog
	 * @param convex Connection
	 */
	public static void showConnectionInfo(Component parent,Convex convex) {
		Async.background(()->connectionInfo(convex))
			.thenAcceptAsync(info->JOptionPane.showMessageDialog(parent, info), Async.EDT);
	}

	private static String connectionInfo(Convex convex) {
		StringBuilder sb=new StringBuilder();
		if (convex instanceof ConvexRemote) {
			sb.append("Remote host: " + convex.getHostAddress() + "\n");
		}
		try {
			sb.append("Sequence:    " + convex.getSequence() + "\n");
		} catch (Exception e1) {
			log.info("Failed to get sequence number");
		}
		sb.append("Account:     " + RT.print(convex.getAddress()) + "\n");
		sb.append("Public Key:  " + RT.toString(convex.getAccountKey()) + "\n");
		sb.append("Connected:   " + convex.isConnected()+"\n");
		return sb.toString();
	}
}
