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
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.AInteger;
import convex.core.lang.Symbols;
import convex.core.util.Text;
import convex.gui.PeerGUI;
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
	private PeerGUI manager;

	public AccountChooserPanel(PeerGUI manager,Convex convex) {
		this.convex=convex;
		this.manager=manager;
		
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
		if (convex.getKeyPair()==null) modeCombo.setSelectedItem("Query");

		add(modeCombo);

		// Balance Info
		balanceLabel = new JLabel("Balance: ");
		balanceLabel.setToolTipText("Convex Coin balance of the currently selected Account");
		add(balanceLabel);

		updateBalance(getSelectedAddress());
	}

	private void updateModel() {
		if (addressModel==null) {
			addressModel = new DefaultComboBoxModel<WalletEntry>();
			addressModel.addElement(WalletEntry.create(convex.getAddress(), convex.getKeyPair()));
			addAddressList(manager);
		} else {
			WalletEntry we=(WalletEntry) addressModel.getSelectedItem();
			addressModel.removeAllElements();
			
			// TODO should use better wallet interface
			addAddressList(manager);
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

	private ComboBoxModel<WalletEntry> addAddressList(PeerGUI mananger) {
		if (manager==null) return addressModel;
		
		ListModel<WalletEntry> m=manager.getWalletPanel().getListModel();
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
		try {
			convex.query(Symbols.STAR_BALANCE).thenAccept(r-> {
				ACell bal=r.getValue();
				String s="<unknown>";
				if (bal instanceof AInteger) {
					s=Text.toFriendlyNumber(((AInteger)bal).longValue());
				}
				balanceLabel.setText(s);
			});
		} catch (Throwable t) {
			balanceLabel.setText(t.getClass().getName());
		}
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
