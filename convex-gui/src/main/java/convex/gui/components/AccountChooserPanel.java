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

	private JLabel balanceLabel;
	
	protected Convex convex;
	protected IWallet wallet;

	public AccountChooserPanel(IWallet wallet,Convex convex) {
		this.convex=convex;
		this.wallet=wallet;
		
		MigLayout layout = new MigLayout("insets 10 10 10 10,fill");
		setLayout(layout);

		{
			JPanel mp=new JPanel();

			// Account selection
			mp.add(new JLabel("Account:"));
	
			addressCombo = new AddressCombo();
			addressCombo.setSelectedItem(convex.getAddress());
			addressCombo.setToolTipText("Select Account for use");
			mp.add(addressCombo);
			
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
			
			// Balance Info
			mp.add(new JLabel("Balance: "));
			balanceLabel = new JLabel("0");
			balanceLabel.setToolTipText("Convex Coin balance of the currently selected Account");
			mp.add(balanceLabel);
			updateBalance(getAddress());

			add(mp,"dock west");
		}
		
		
		// Blank space
		add(new JPanel(),"growx");
		
		// Mode selection	
		{
			JPanel mp=new JPanel();
			mp.setBorder(null);
			modeCombo = new JComboBox<String>();
			modeCombo.setToolTipText("Use Transact to execute transactions (uses Convex Coins).\n\n"
					+ "Use Query to compute results without changing on-chain state (free).");
			modeCombo.addItem("Transact");
			modeCombo.addItem("Query");
			if (convex.getKeyPair()==null) modeCombo.setSelectedItem("Query");
			mp.add(modeCombo);
			add(mp,"dock east");
		}
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
					s=Text.toFriendlyBalance(((AInteger)bal).longValue());
				}
				if (r.getErrorCode()!=null) {
					s="<"+r.getErrorCode()+">";
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
