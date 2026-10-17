package convex.gui.components.account;

import java.awt.event.ItemEvent;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.cvm.ops.Special;
import convex.core.data.AccountKey;
import convex.gui.components.BalanceLabel;
import convex.gui.components.ConnectPanel;
import convex.gui.components.DropdownMenu;
import convex.gui.keys.KeyAccess;
import convex.gui.keys.KeyRing;
import convex.gui.utils.Async;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Panel allowing the selection of account and query mode for a Convex connection
 */
@SuppressWarnings("serial")
public class AccountChooserPanel extends JPanel implements AutoCloseable {
	
	private static final Logger log = LoggerFactory.getLogger(AccountChooserPanel.class.getName());


	private JComboBox<String> modeCombo;
	public final AddressCombo addressCombo;
	public final KeyPairCombo keyCombo;

	private BalanceLabel balanceLabel;

	protected Convex convex;

	private static final int BALANCE_REFRESH_MS = 5000;
	private final Timer balanceRefreshTimer;

    AWalletEntry previousSelection = null;

	/** True while showing a signer found elsewhere, so selecting it doesn't ask again. EDT only. */
	private boolean showingSigner=false;


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
				AWalletEntry we=(AWalletEntry)e.getItem();
				if (e.getStateChange()==ItemEvent.DESELECTED) {
					previousSelection=we;
					return;
				} else if (!showingSigner) {
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

			// Periodic refresh so balance stays current when updated outside this panel
			balanceRefreshTimer = new Timer(BALANCE_REFRESH_MS, e -> updateBalance());
			balanceRefreshTimer.start();

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
			setSeqButton.addActionListener(e -> Async.whileBusy(e,
				Async.background(convex::getSequence).thenAcceptAsync(this::askNextSequence, Async.EDT)));
			popupMenu.add(setSeqButton);

			JMenuItem reconnectButton = new JMenuItem("Reconnect",Toolkit.menuIcon(0xe157));
			reconnectButton.addActionListener(e -> Async.whileBusy(e, Async.background(()->{
				convex.reconnect();
				return null;
			})));
			popupMenu.add(reconnectButton);
			
			JMenuItem connInfoButton = new JMenuItem("Connection Info...",Toolkit.menuIcon(0xe157));
			connInfoButton.addActionListener(e -> {
				try {
					ConnectPanel.showConnectionInfo(this, convex);
				} catch (Exception ex) {
					log.info("Reconnect failed",ex);
				}
			});
			popupMenu.add(connInfoButton);



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
					+ "Use Query to compute results without changing on-chain state (free, including zero-balance accounts).\n\n"
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
		// Continue on the EDT, since choosing a key may ask for its password
		convex.query(Special.forSymbol(Symbols.STAR_KEY)).thenAcceptAsync(r-> {
			if (r.isError()) {
				// ignore?
				System.err.println("Account key query failed: "+r);
				setKeyPair(null);
			} else {
				AccountKey ak=AccountKey.parse(r.getValue()); // might be null, will clear key
				AWalletEntry we=KeyRing.get().find(ak);
				setKeyPair(we);
			}
		}, Async.EDT);

		updateBalance();
	}
	
	public void setKeyPair(AWalletEntry we) {
		// In case we are re-entering
		if (we!=keyCombo.getWalletEntry()) {
			keyCombo.setSelectedItem(we);
			return;
		}
		
		if (we==null) {
			convex.setKeyPair(null);
		} else {
			AKeyPair kp=KeyAccess.unlockForUse(this, we);
			if (kp==null) {
				keyCombo.setSelectedItem(previousSelection);
				return;
			}
			convex.setKeyPair(kp);
		}
	}

	/**
	 * Shows a key pair the connection now signs with, found elsewhere (e.g. when
	 * the window opened), and switches to Transact mode. Doesn't ask for the key
	 * again. Call on the EDT.
	 * @param kp Key pair set on the connection
	 */
	public void showSigner(AKeyPair kp) {
		AWalletEntry we=KeyRing.get().find(kp.getAccountKey());
		showingSigner=true;
		try {
			// Via the model: the combo ignores items not in its list, such as a key outside the key ring
			keyCombo.getModel().setSelectedItem((we!=null)?we:new HotWalletEntry(kp,null));
		} finally {
			showingSigner=false;
		}
		modeCombo.setSelectedItem("Transact");
	}

	public void updateBalance() {
		updateBalance(getAddress());
	}

	/** Stops the balance refresh. Called when the owning window closes. */
	@Override
	public void close() {
		balanceRefreshTimer.stop();
	}

	private void updateBalance(Address a) {
		balanceLabel.showResult(convex.query(Special.get("*balance*"),a));
	}

	/** Asks for the next sequence number to use, given the current one. Runs on the EDT. */
	private void askNextSequence(long seq) {
		String s = JOptionPane.showInputDialog(this, "Current sequence number is "+seq+", so the next sequence number expected is "+(seq+1), "Enter next sequence number", JOptionPane.QUESTION_MESSAGE);
		if (s==null) return;
		try {
			convex.setNextSequence(Long.parseLong(s));
		} catch (NumberFormatException e) {
			JOptionPane.showMessageDialog(this, "Invalid sequence number");
		}
		convex.clearSequence();
	}

	public String getMode() {
		return (String) modeCombo.getSelectedItem();
	}

	public Convex getConvex() {
		return convex;
	}
}
