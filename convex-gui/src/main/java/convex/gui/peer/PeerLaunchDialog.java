package convex.gui.peer;

import java.awt.Color;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.gui.components.Toast;
import convex.gui.components.account.KeyPairCombo;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

public class PeerLaunchDialog {

	public static void runLaunchDialog(JComponent parent) {
		JPanel pan=new JPanel();
		pan.setLayout(new MigLayout("fill,wrap 3","","[fill]10[fill][40]"));
		
		pan.add(new JLabel("Number of Peers:"));
		JSpinner peerCountSpinner = new JSpinner();
		// Note: about 300 max number of clients before hitting juice limits for account creation
		peerCountSpinner.setModel(new SpinnerNumberModel(PeerGUI.DEFAULT_NUM_PEERS, 1, 100, 1));
		pan.add(peerCountSpinner);
		pan.add(Toolkit.makeHelp("Select a number of peers to include in the genesis state and launch initially. More can be added later. 3-5 recommended for local devnet testing"));
	
		pan.add(new JLabel("Genesis Key:   "));
		AKeyPair kp=AKeyPair.generate();
		KeyPairCombo keyField=KeyPairCombo.create(kp);
	
		pan.add(keyField);
		pan.add(Toolkit.makeHelp("Select genesis key for the network. The genesis key will be the key used for the first peer and initial governance accounts."));
	
		pan.add(new JPanel());
		
		JButton randomise=new JButton("Randomise",SymbolIcon.get(0xe863,Toolkit.SMALL_ICON_SIZE)); 
		randomise.addActionListener(e->{
			AKeyPair newKP=AKeyPair.generate();
			// System.err.println("Generated key "+newKP.getAccountKey());
			// Note we go to the model directly, JComboBox doeesn't like
			// setting a selected item to something not in the list when not editable
			keyField.getModel().setSelectedItem(HotWalletEntry.create(newKP,"Random genesis key pair for testing"));
		});
		pan.add(randomise);
		pan.add(Toolkit.makeHelp("Randomise the genesis key. Fine for testing purposes."));
	
		int result = JOptionPane.showConfirmDialog(parent, pan, 
	               "Peer Launch Details", 
	               JOptionPane.OK_CANCEL_OPTION, 
	               JOptionPane.QUESTION_MESSAGE,
	               SymbolIcon.get(0xeb9b,Toolkit.ICON_SIZE));
	    if (result == JOptionPane.OK_OPTION) {
	    	try {
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
	    		
	       		kp=we.getKeyPair();
	    		
	    		PeerGUI.launchPeerGUI(numPeers, kp,false);
	    	} catch (InterruptedException e) {
	    		Thread.currentThread().interrupt();
	    	} catch (Exception e) {
	    		e.printStackTrace();
	    		JOptionPane.showMessageDialog(parent, "Peer launch failed\n\nReason : "+e.getMessage());
	    	}
	    }
	}

}
