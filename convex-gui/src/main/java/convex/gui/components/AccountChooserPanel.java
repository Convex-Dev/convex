package convex.gui.components;

import java.awt.FlowLayout;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ListModel;

import convex.core.State;
import convex.core.crypto.WalletEntry;
import convex.core.data.Address;
import convex.core.util.Text;
import convex.gui.manager.PeerGUI;
import convex.gui.manager.mainpanels.WalletPanel;

/**
 * Panel allowing the selection of account and query mode
 */
@SuppressWarnings("serial")
public class AccountChooserPanel extends JPanel {

	private JComboBox<String> modeCombo;
	public JComboBox<WalletEntry> addressCombo;
	private JLabel lblNewLabel_1;
	private JLabel lblNewLabel;

	private ComboBoxModel<WalletEntry> addressModel = createAddressList(WalletPanel.getListModel());
	private JLabel balanceLabel;

	public AccountChooserPanel() {
		FlowLayout flowLayout = new FlowLayout();
		flowLayout.setAlignment(FlowLayout.LEFT);
		setLayout(flowLayout);

		modeCombo = new JComboBox<String>();
		modeCombo.setToolTipText("Use Transact to execute transactions (uses cash).\n\n"
				+ "Use Query to compute results without changing on-chain state (free).");
		modeCombo.addItem("Transact");
		modeCombo.addItem("Query");

		lblNewLabel_1 = new JLabel("Mode:");
		add(lblNewLabel_1);
		add(modeCombo);

		lblNewLabel = new JLabel("Account:");
		add(lblNewLabel);

		addressCombo = new JComboBox<WalletEntry>();
		addressCombo.setEditable(false);
		add(addressCombo);
		addressCombo.setModel(addressModel);

		balanceLabel = new JLabel("Balance: ");
		add(balanceLabel);

		PeerGUI.getStateModel().addPropertyChangeListener(pc -> {
			updateBalance((State) pc.getNewValue(), getSelectedAddress());
		});

		addressCombo.addItemListener(e -> {
			updateBalance(PeerGUI.getLatestState(), getSelectedAddress());
		});

		updateBalance(PeerGUI.getLatestState(), getSelectedAddress());
	}

	public Address getSelectedAddress() {
		WalletEntry we = (WalletEntry) addressModel.getSelectedItem();
		return (we == null) ? null : we.getAddress();
	}
	
	public boolean selectAddress(Address a) {
		for (int i = 0; i < addressModel.getSize(); i++) {
			WalletEntry we = addressModel.getElementAt(i);
			if (we.getAddress().equals(a)) {
				addressModel.setSelectedItem(we);
				return true;
			};
		}
		return false;
	}

	private ComboBoxModel<WalletEntry> createAddressList(ListModel<WalletEntry> m) {
		int n = m.getSize();
		DefaultComboBoxModel<WalletEntry> cm = new DefaultComboBoxModel<WalletEntry>();
		for (int i = 0; i < n; i++) {
			WalletEntry we = m.getElementAt(i);
			cm.addElement(we);
		}
		cm.addElement(null);
		return cm;
	}

	private void updateBalance(State s, Address a) {
		if ((s == null) || (a == null)) {
			balanceLabel.setText("Balance: <not available>");
		} else {
			Long amt= s.getBalance(a);
			balanceLabel.setText("Balance: " + ((amt==null)?"Null":Text.toFriendlyNumber(amt)));
		}
	}

	public String getMode() {
		return (String) modeCombo.getSelectedItem();
	}

	public WalletEntry getWalletEntry() {
		return (WalletEntry) addressCombo.getSelectedItem();
	}
}
