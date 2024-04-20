package convex.gui.peer.mainpanels;

import java.awt.BorderLayout;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.ListModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.BasicWalletEntry;
import convex.core.data.Address;
import convex.gui.components.ActionPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.WalletComponent;
import convex.gui.peer.PeerGUI;

@SuppressWarnings("serial")
public class WalletPanel extends JPanel {
	
	private static final Logger log = LoggerFactory.getLogger(WalletPanel.class.getName());

	public static BasicWalletEntry HERO;

	private DefaultListModel<BasicWalletEntry> listModel = new DefaultListModel<>();;
	ScrollyList<BasicWalletEntry> walletList;

	public void addWalletEntry(BasicWalletEntry we) {
		listModel.addElement(we);
	}

	/**
	 * Create the panel.
	 */
	public WalletPanel(PeerGUI manager) {
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);

		// new wallet button
		JButton btnNew = new JButton("New Account");
		toolBar.add(btnNew);
		btnNew.addActionListener(e -> {
			Convex convex=manager.getDefaultConvex();
			AKeyPair newKP=AKeyPair.generate();
			try {
				Address addr=convex.createAccountSync(newKP.getAccountKey());
				listModel.addElement(BasicWalletEntry.create(addr,newKP));
			} catch (Throwable t) {
				log.warn("Exception creating account: ",t);
			}
		});


		// create and add ScrollyList
		walletList = new ScrollyList<BasicWalletEntry>(listModel, we -> new WalletComponent(manager,we));
		add(walletList, BorderLayout.CENTER);
	}

	public ListModel<BasicWalletEntry> getListModel() {
		return listModel;
	}

}
