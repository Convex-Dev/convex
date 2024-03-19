package convex.gui.components;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ListModel;

import convex.api.Convex;
import convex.core.crypto.WalletEntry;
import convex.core.data.Address;
import convex.core.util.Text;
import convex.gui.PeerGUI;
import convex.gui.manager.mainpanels.WalletPanel;
import net.miginfocom.swing.MigLayout;

/**
 * Panel allowing the selection of account and query mode
 */
@SuppressWarnings("serial")
public class AccountChooserPanel extends JPanel {

	private JComboBox<String> modeCombo;
	public JComboBox<WalletEntry> addressCombo;
	private JLabel lblMode;
	private JLabel lblNewLabel;

	private DefaultComboBoxModel<WalletEntry> addressModel;
	private JLabel balanceLabel;
	
	private Convex convex;

	public AccountChooserPanel(Convex convex) {
		this.convex=convex;
		
		MigLayout flowLayout = new MigLayout();
		setLayout(flowLayout);


		// Account selection
		
		lblNewLabel = new JLabel("Account:");
		add(lblNewLabel);

		addressCombo = new JComboBox<WalletEntry>();
		addressCombo.setEditable(true);
		add(addressCombo);
		addressCombo.setToolTipText("Select Account for use");
		updateModel();
		addressCombo.setModel(addressModel);
		addressCombo.addFocusListener(new FocusListener() {
			@Override
			public void focusGained(FocusEvent e) {
				updateModel();
			}

			@Override
			public void focusLost(FocusEvent e) {
				// Ignore		
			}
		});

		addressCombo.addItemListener(e -> {
			updateBalance(getSelectedAddress());
		});
		
		// Mode selection
		
		lblMode = new JLabel("Mode:");
		add(lblMode);

		modeCombo = new JComboBox<String>();
		modeCombo.setToolTipText("Use Transact to execute transactions (uses Convex Coins).\n\n"
				+ "Use Query to compute results without changing on-chain state (free).");
		modeCombo.addItem("Transact");
		modeCombo.addItem("Query");

		add(modeCombo);


		// Balance Info
		balanceLabel = new JLabel("Balance: ");
		balanceLabel.setToolTipText("Convex Coin balance of the currently selected Account");
		add(balanceLabel);

		//PeerGUI.getStateModel().addPropertyChangeListener(pc -> {
		//	updateBalance(getSelectedAddress());
		//});

		// updateBalance(getSelectedAddress());
	}

	private void updateModel() {
		if (addressModel==null) {
			addressModel = new DefaultComboBoxModel<WalletEntry>();
			addAddressList(WalletPanel.getListModel());
		} else {
			WalletEntry we=(WalletEntry) addressModel.getSelectedItem();
			addressModel.removeAllElements();
			addAddressList(WalletPanel.getListModel());
			if (we!=null) {
				addressModel.setSelectedItem(we);
			}
		}
		addressCombo.setModel(addressModel);
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
				updateBalance(a);
				return true;
			};
		}
		return false;
	}

	private ComboBoxModel<WalletEntry> addAddressList(ListModel<WalletEntry> m) {
		int n = m.getSize();
		DefaultComboBoxModel<WalletEntry> cm = addressModel;
		for (int i = 0; i < n; i++) {
			WalletEntry we = m.getElementAt(i);
			cm.addElement(we);
		}
		cm.addElement(null);
		return cm;
	}
	
	public void updateBalance() {
		updateBalance(getSelectedAddress());
	}

	private void updateBalance(Address a) {
		PeerGUI.runWithLatestState(s->{
			if ((s == null) || (a == null)) {
				balanceLabel.setText("Balance: <not available>");
			} else {
				Long amt= s.getBalance(a);
				balanceLabel.setText("Balance: " + ((amt==null)?"Null":Text.toFriendlyNumber(amt)));
			}
		});
	}

	public String getMode() {
		return (String) modeCombo.getSelectedItem();
	}

	public WalletEntry getWalletEntry() {
		return (WalletEntry) addressCombo.getSelectedItem();
	}

	public Convex getConvex() {
		return convex;
	}
}
