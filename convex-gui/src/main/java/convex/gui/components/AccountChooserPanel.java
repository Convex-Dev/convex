package convex.gui.components;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.crypto.wallet.IWallet;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.AInteger;
import convex.core.lang.ops.Special;
import convex.core.text.Text;
import convex.gui.components.account.AddressCombo;
import net.miginfocom.swing.MigLayout;

/**
 * Panel allowing the selection of account and query mode for a Convex connection
 */
@SuppressWarnings("serial")
public class AccountChooserPanel extends JPanel {

	private JComboBox<String> modeCombo;
	public final AddressCombo addressCombo;
	private JLabel lblMode;
	private JLabel lblNewLabel;

	private JLabel balanceLabel;
	
	protected Convex convex;
	protected IWallet wallet;

	public AccountChooserPanel(IWallet wallet,Convex convex) {
		this.convex=convex;
		this.wallet=wallet;
		
		MigLayout layout = new MigLayout();
		setLayout(layout);


		// Account selection
		
		lblNewLabel = new JLabel("Account:");
		add(lblNewLabel);

		addressCombo = new AddressCombo();
		addressCombo.setSelectedItem(convex.getAddress());
		addressCombo.setToolTipText("Select Account for use");
		add(addressCombo);
		
		addressCombo.addFocusListener(new FocusListener() {
			@Override
			public void focusGained(FocusEvent e) {
			}

			@Override
			public void focusLost(FocusEvent e) {
				// Ignore		
			}
		});

		addressCombo.addItemListener(e -> {
			updateAddress(addressCombo.getAddress());
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

		updateBalance(getAddress());
	}

	public Address getAddress() {
		return convex.getAddress();
	}
	
	public void updateAddress(Address a) {
		convex.setAddress(a);
		
		updateBalance();
	}
	
	public void updateBalance() {
		updateBalance(getAddress());
	}

	private void updateBalance(Address a) {
		try {
			convex.query(Special.get("*balance*"),a).thenAccept(r-> {
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

	public Convex getConvex() {
		return convex;
	}
}
