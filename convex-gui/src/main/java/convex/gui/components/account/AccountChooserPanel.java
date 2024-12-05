package convex.gui.components.account;

import java.awt.event.ItemEvent;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.cvm.ops.Special;
import convex.core.data.AccountKey;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.exceptions.ResultException;
import convex.gui.components.BalanceLabel;
import convex.gui.components.DropdownMenu;
import convex.gui.keys.KeyRingPanel;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Panel allowing the selection of account and query mode for a Convex connection
 */
@SuppressWarnings("serial")
public class AccountChooserPanel extends JPanel {

	private JComboBox<String> modeCombo;
	public final AddressCombo addressCombo;
	public final KeyPairCombo keyCombo;

	private BalanceLabel balanceLabel;
	
	protected Convex convex;

	public AccountChooserPanel(Convex convex) {
		this.convex=convex;
		
		MigLayout layout = new MigLayout();
		setLayout(layout);

		{
			JPanel mp=new JPanel();
			mp.setLayout(new MigLayout());

			// Account selection
			mp.add(new JLabel("Account:"));
	
			Address address=convex.getAddress();
			addressCombo = new AddressCombo();
			addressCombo.setSelectedItem(address);
			addressCombo.setToolTipText("Select Account for use");
			addressCombo.addItemListener(e -> {
				updateAddress(addressCombo.getAddress());
			});
			mp.add(addressCombo);
			
			keyCombo=KeyPairCombo.forConvex(convex);
			keyCombo.setToolTipText("Select a key pair from your Keyring. This will be used to sign transactions.");
			keyCombo.addItemListener(e->{
				if (e.getStateChange()==ItemEvent.DESELECTED) {
					// key pair was deselected and/or set to null
					setKeyPair(null);
					return;
				};
				AWalletEntry we=(AWalletEntry)e.getItem();
				if (we!=keyCombo.getWalletEntry()) {
					setKeyPair(we);
				}
			});
			mp.add(keyCombo);

			
			// Balance Info
			mp.add(new JLabel("Balance: "));
			balanceLabel = new BalanceLabel();
			balanceLabel.setToolTipText("Convex Coin balance of the currently selected Account");
			mp.add(balanceLabel);
			updateBalance(getAddress());

			add(mp,"dock west");
		}
		
		// Settings
		{
			//////////////////////////////////
			// Settings Popup menu for peer
			JPopupMenu popupMenu = new JPopupMenu();

			JMenuItem clearSeqButton = new JMenuItem("Clear sequence",Toolkit.menuIcon(0xe9d5));
			clearSeqButton.addActionListener(e -> {
				convex.clearSequence();
			});
			popupMenu.add(clearSeqButton);
			
			JMenuItem setSeqButton = new JMenuItem("Set sequence...",Toolkit.menuIcon(0xe6d4));
			setSeqButton.addActionListener(e -> {
				try {
					long seq=convex.getSequence();
					String s = JOptionPane.showInputDialog(this, "Current sequence number is "+seq+", so the next sequence number expected is "+(seq+1), "Enter next sequence number", JOptionPane.QUESTION_MESSAGE);
					if (s==null) return;
					
					long nextSeq=Long.parseLong(s);
					
					convex.setNextSequence(nextSeq);
					System.err.println("Sequence number set: "+convex.getSequence());
				} catch (NumberFormatException e1) {
					JOptionPane.showMessageDialog(this, "Invalid sequence number");
				}catch (ResultException e1) {
					e1.printStackTrace();
				} catch (InterruptedException e1) {
					Thread.currentThread().interrupt();
				}
				convex.clearSequence();
			});
			popupMenu.add(setSeqButton);


			DropdownMenu dm = new DropdownMenu(popupMenu,Toolkit.SMALL_ICON_SIZE);
			add(dm);
		}
		
		// Mode selection	
		{
			JPanel mp=new JPanel();
			mp.setLayout(new MigLayout());
			mp.setBorder(null);
			modeCombo = new JComboBox<String>();
			modeCombo.setToolTipText("Use Transact to execute transactions (uses Convex Coins).\n\n"
					+ "Use Query to compute results without changing on-chain state (free).\n\n"
					+ "Use Prepare to prepare the transcation without submitting."
					);
			modeCombo.addItem("Transact");
			modeCombo.addItem("Query");
			modeCombo.addItem("Prepare");
			
			modeCombo.setFocusable(false);
			// modeCombo.addItem("Prepare...");
			if (convex.getKeyPair()==null) modeCombo.setSelectedItem("Query");
			mp.add(modeCombo);
			add(mp, "dock east");
		}
	}

	public Address getAddress() {
		return convex.getAddress();
	}
	
	public void updateAddress(Address a) {
		convex.setAddress(a);
		convex.query(Special.forSymbol(Symbols.STAR_KEY)).thenAcceptAsync(r-> {
			if (r.isError()) {
				// ignore?
				System.err.println("Account key query failed: "+r);
				setKeyPair(null);
			} else {
				AccountKey ak=AccountKey.parse(r.getValue()); // might be null, will clear key
				AWalletEntry we=KeyRingPanel.getKeyRingEntry(ak);
				setKeyPair(we);
			}
		});
		
		updateBalance();
	}
	
	public void setKeyPair(AWalletEntry we) {
		if (we==null) {
			convex.setKeyPair(null);
		} else {
			AKeyPair kp;
			if (we.isLocked()) {
				boolean unlock=UnlockWalletDialog.offerUnlock(this, we);
				if (!unlock) {
					convex.setKeyPair(null);
					keyCombo.setSelectedItem(null);
					return;
				}

				kp=we.getKeyPair();
				we.lock();
			} else {
				kp=we.getKeyPair();
			}
			convex.setKeyPair(kp);
			keyCombo.setSelectedItem(kp);
		}
	}
	
	public void updateBalance() {
		updateBalance(getAddress());
	}

	private void updateBalance(Address a) {
		try {
			convex.query(Special.get("*balance*"),a).thenAccept(r-> {
				balanceLabel.setFromResult(r);
			});
		} catch (NullPointerException t) {
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
