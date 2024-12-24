package convex.gui.peer;

import java.awt.Color;
import java.net.InetSocketAddress;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.util.FileUtils;
import convex.gui.components.FilePicker;
import convex.gui.components.HostCombo;
import convex.gui.components.Toast;
import convex.gui.components.account.KeyPairCombo;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.net.IPUtils;
import net.miginfocom.swing.MigLayout;

public class PeerLaunchDialog {

	public static void runLaunchDialog(JComponent parent) {
		// Local testnet options
		
		JPanel testNetPanel=new JPanel();
		testNetPanel.setLayout(new MigLayout("fill,wrap 3","","[fill]10[fill][40]"));
		
		testNetPanel.add(new JLabel("Number of Peers:"));
		JSpinner peerCountSpinner = new JSpinner();
		// Note: about 300 max number of clients before hitting juice limits for account creation
		peerCountSpinner.setModel(new SpinnerNumberModel(PeerGUI.DEFAULT_NUM_PEERS, 1, 100, 1));
		testNetPanel.add(peerCountSpinner);
		testNetPanel.add(Toolkit.makeHelp("Select a number of peers to include in the genesis state and launch initially. More can be added later. 3-5 recommended for local devnet testing"));
	
		testNetPanel.add(new JLabel("Genesis Key:   "));
		KeyPairCombo keyField=KeyPairCombo.create();
	
		testNetPanel.add(keyField);
		testNetPanel.add(Toolkit.makeHelp("Select genesis key for the network. The genesis key will be the key used for the first peer and initial governance accounts."));
	
		testNetPanel.add(new JPanel());
		
		JButton randomise=new JButton("Randomise",SymbolIcon.get(0xe863,Toolkit.SMALL_ICON_SIZE)); 
		randomise.addActionListener(e->{
			AKeyPair newKP=AKeyPair.generate();
			// System.err.println("Generated key "+newKP.getAccountKey());
			// Note we go to the model directly, JComboBox doeesn't like
			// setting a selected item to something not in the list when not editable
			keyField.getModel().setSelectedItem(HotWalletEntry.create(newKP,"Random genesis key pair for testing"));
		});
		testNetPanel.add(randomise);
		testNetPanel.add(Toolkit.makeHelp("Randomise the genesis key. Fine for testing purposes."));
		
		
		// Temporary peer options
		JPanel joinPanel=new JPanel();
		joinPanel.setLayout(new MigLayout("fill,wrap 3","","[fill]10[fill][40]"));
		joinPanel.add(new JLabel("Source Peer:"));
		HostCombo hostField=new HostCombo();
		hostField.setToolTipText("Enter a peer address to join e.g. peer.convex.live:18888");
		joinPanel.add(hostField);
		joinPanel.add(Toolkit.makeHelp("Select an existing peer to join with the new peer. Should be a trusted source for the global state and current consensus ordering."));

		joinPanel.add(new JLabel("Peer Key:   "));
		KeyPairCombo peerKeyField=KeyPairCombo.create();
		joinPanel.add(peerKeyField);
		joinPanel.add(Toolkit.makeHelp("Select peer key for the new peer."));
		
		joinPanel.add(new JLabel("Etch Store:   "));
		FilePicker filePicker=new FilePicker(FileUtils.getFile("~/.convex/etch.db").getAbsolutePath());
		joinPanel.add(filePicker);
		joinPanel.add(Toolkit.makeHelp("Select Etch database file for peer operation."));

		
		JTabbedPane tabs=new JTabbedPane();
		tabs.add(testNetPanel,"Local Testnet");
		tabs.add(joinPanel,"Join Network");
		
		int result = JOptionPane.showConfirmDialog(parent, tabs, 
	               "Peer Launch Details", 
	               JOptionPane.OK_CANCEL_OPTION, 
	               JOptionPane.QUESTION_MESSAGE,
	               SymbolIcon.get(0xeb9b,Toolkit.ICON_SIZE));
	    if (result == JOptionPane.OK_OPTION) {
	    	try {
		    	if (tabs.getSelectedComponent()==testNetPanel) {
		    		int numPeers=(Integer)peerCountSpinner.getValue();
		    		AWalletEntry we=keyField.getWalletEntry();
		    		if (we==null) throw new IllegalStateException("No key pair selected");
		    		
		    		if (we.isLocked()) {
						boolean unlocked= UnlockWalletDialog.offerUnlock(parent,we);
						if (!unlocked) {
							Toast.display(parent, "Launch cancelled: Locked genesis key", Color.RED);
							return;
						}
		    		}
		    		
		       		AKeyPair kp=we.getKeyPair();
		    		PeerGUI.launchPeerGUI(numPeers, kp);
		    	} else if (tabs.getSelectedComponent()==joinPanel) {
		    		String host=hostField.getText();
		    		InetSocketAddress sa=IPUtils.toInetSocketAddress(host);
		    		if (sa==null) throw new IllegalArgumentException("Invalid host address for joining");
		    		
		    		AWalletEntry we=peerKeyField.getWalletEntry();
		    		if (we==null) throw new IllegalArgumentException("No peer key selected");
		    		
		    		PeerGUI.launchPeerGUI(sa,we);
		    	}
	    	} catch (InterruptedException e) {
	    		Thread.currentThread().interrupt();
	    	} catch (Exception e) {
	    		e.printStackTrace();
	    		JOptionPane.showMessageDialog(parent, "Peer launch failed\n\nReason : "+e.getMessage());
	    	}
	    }
	}

}
