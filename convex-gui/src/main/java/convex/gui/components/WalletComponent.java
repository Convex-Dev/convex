package convex.gui.components;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.util.Text;
import convex.gui.PeerGUI;
import convex.gui.client.ConvexClient;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class WalletComponent extends BaseListComponent {
	
	@SuppressWarnings("unused")
	private static final Logger log = LoggerFactory.getLogger(WalletComponent.class.getName());

	Icon icon = Toolkit.LOCKED_ICON;

	JButton lockButton;
	JButton replButton;

	WalletEntry walletEntry;

	JPanel buttons = new JPanel();

	private Address address;
	PeerGUI manager;

	public WalletComponent(PeerGUI manager,WalletEntry initialWalletEntry) {
		this.walletEntry = initialWalletEntry;
		this.manager=manager;
		address = walletEntry.getAddress();

		setLayout(new MigLayout("fillx"));

		// identicon
		JLabel identicon = new Identicon(walletEntry.getIdenticonHash());
		JPanel idPanel=new JPanel();
		idPanel.add(identicon);
		add(idPanel,"west"); // add to MigLayout

		// Wallet Address and info fields
		JPanel cPanel = new JPanel();
		cPanel.setLayout(new MigLayout("fillx"));
		CodeLabel addressLabel = new CodeLabel(address.toString());
		addressLabel.setFont(Toolkit.MONO_FONT);
		cPanel.add(addressLabel,"span");
		CodeLabel infoLabel = new CodeLabel(getInfoString());
		cPanel.add(infoLabel,"span,growx");
		add(cPanel,"grow,shrink"); // add to MigLayout

		manager.getStateModel().addPropertyChangeListener(e -> {
			infoLabel.setText(getInfoString());
		});
		
		///// Buttons
		// REPL button
		replButton = new JButton("");
		buttons.add(replButton);
		replButton.setIcon(Toolkit.REPL_ICON);
		replButton.addActionListener(e -> {
			ConvexClient c= ConvexClient.launch(manager.connectClient(walletEntry.getAddress(),walletEntry.getKeyPair()));
			c.tabs.setSelectedComponent(c.replPanel);
		});
		replButton.setToolTipText("Launch a client REPL for this account");

		// lock button
		lockButton = new JButton("");
		buttons.add(lockButton);
		lockButton.setIcon(walletEntry.isLocked() ? Toolkit.LOCKED_ICON : Toolkit.UNLOCKED_ICON);
		resetTooltipTExt(lockButton);
		lockButton.addActionListener(e -> {
			if (walletEntry.isLocked()) {
				UnlockWalletDialog dialog = UnlockWalletDialog.show(this);
				char[] passPhrase = dialog.getPassPhrase();
				try {
					walletEntry = walletEntry.unlock(passPhrase);
					icon = Toolkit.UNLOCKED_ICON;
				} catch (Throwable e1) {
					JOptionPane.showMessageDialog(this, "Unable to unlock wallet: " + e1.getMessage());
				}
			} else {
				try {
					walletEntry = walletEntry.lock();
				} catch (IllegalStateException e1) {
					// OK, must be already locked.
				}
				icon = Toolkit.LOCKED_ICON;
			}
			resetTooltipTExt(lockButton);
			lockButton.setIcon(icon);
		});
		
		// Menu Button
		JPopupMenu menu=new JPopupMenu();
		JMenuItem m1=new JMenuItem("Edit...");
		menu.add(m1);
		JMenuItem m2=new JMenuItem("Show seed...");
		m2.addActionListener(e-> {
			AKeyPair kp=walletEntry.getKeyPair();
			if (kp!=null) {
				StringBuilder sb=new StringBuilder();
				sb.append("\nEd25519 private seed:\n");
				sb.append("\n"+kp.getSeed()+"\n\n");
				sb.append("\nWarning: keep this private, it can be used to control your accounts\n");
				JTextArea text = new JTextArea(sb.toString());
				JOptionPane.showMessageDialog(WalletComponent.this, text,"Private Seed",JOptionPane.INFORMATION_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(WalletComponent.this, "Seed not available","Warning",JOptionPane.WARNING_MESSAGE);
			}
		});
		menu.add(m2);

		DropdownMenu menuButton=new DropdownMenu(menu);
		buttons.add(menuButton);
		
		// panel of buttons on right
		add(buttons,"east"); // add to MigLayout
	}

	private void resetTooltipTExt(JComponent b) {
		if (walletEntry.isLocked()) {
			b.setToolTipText("Unlock");
		} else {
			b.setToolTipText("Lock");
		}
	}

	private String getInfoString() {
		StringBuilder sb=new StringBuilder();
		State s=manager.getLatestState();
		AccountStatus as=s.getAccount(address);
		if (as!=null) {
			Long bal=as.getBalance();
			sb.append("Public Key: " + walletEntry.getAccountKey()+"\n");
			sb.append("Balance:    " + Text.toFriendlyNumber(bal));
		}
		
		//sb.append("\n");
		//sb.append("Key: "+walletEntry.getAccountKey()+ "   Controller: "+as.getController());
		return sb.toString();
	}

}
