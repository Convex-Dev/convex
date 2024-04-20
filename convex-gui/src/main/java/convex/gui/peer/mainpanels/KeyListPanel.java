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
import convex.gui.components.ConnectPanel;
import convex.gui.components.ScrollyList;
import convex.gui.components.WalletComponent;

@SuppressWarnings("serial")
public class KeyListPanel extends JPanel {
	
	private static final Logger log = LoggerFactory.getLogger(KeyListPanel.class.getName());

	public static BasicWalletEntry HERO;

	private DefaultListModel<BasicWalletEntry> listModel = new DefaultListModel<>();;
	ScrollyList<BasicWalletEntry> walletList;

	public void addWalletEntry(BasicWalletEntry we) {
		listModel.addElement(we);
	}
	
	Convex convex;

	/**
	 * Create the panel.
	 */
	public KeyListPanel(Convex dataSource) {
		this.convex=dataSource;
		setLayout(new BorderLayout(0, 0));

		JPanel toolBar = new ActionPanel();
		add(toolBar, BorderLayout.SOUTH);
		
		// create and add ScrollyList
		walletList = new ScrollyList<BasicWalletEntry>(listModel, we -> new WalletComponent(KeyListPanel.this.convex,we));
		add(walletList, BorderLayout.CENTER);


		// new wallet button
		JButton btnNew = new JButton("New Account");
		toolBar.add(btnNew);
		btnNew.addActionListener(e -> {
			AKeyPair newKP=AKeyPair.generate();
			try {
				Address addr=convex.createAccountSync(newKP.getAccountKey());
				listModel.addElement(BasicWalletEntry.create(addr,newKP));
			} catch (Throwable t) {
				log.warn("Exception creating account: ",t);
			}
		});
		
		// new wallet button
		JButton btnRefresh = new JButton("Refresh Info");
		toolBar.add(btnRefresh);
		btnRefresh.addActionListener(e -> {
			if (convex==null) {
				setConvex(ConnectPanel.tryConnect(this, "Connect to Peer for key info"));
			}
			walletList.refreshList();
		});
	}
	
	public void setConvex(Convex c) {
		this.convex=c;
	}

	public ListModel<BasicWalletEntry> getListModel() {
		return listModel;
	}

}
