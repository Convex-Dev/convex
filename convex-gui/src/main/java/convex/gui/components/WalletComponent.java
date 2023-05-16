package convex.gui.components;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.State;
import convex.core.crypto.WalletEntry;
import convex.core.data.Address;
import convex.core.util.Text;
import convex.gui.client.ConvexClient;
import convex.gui.manager.PeerGUI;
import convex.gui.utils.Toolkit;

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

	public WalletComponent(WalletEntry initialWalletEntry) {
		this.walletEntry = initialWalletEntry;
		address = walletEntry.getAddress();

		setLayout(new BorderLayout());
		
		// REPL button
		replButton = new JButton("");
		buttons.add(replButton);
		replButton.setIcon(Toolkit.REPL_ICON);
		replButton.addActionListener(e -> {
			ConvexClient c= ConvexClient.launch(PeerGUI.connectClient(walletEntry.getAddress(),walletEntry.getKeyPair()));
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
		DropdownMenu menuButton=new DropdownMenu(menu);
		buttons.add(menuButton);
		
		// panel of buttons on right
		add(buttons, BorderLayout.EAST);

		// identicon
		JLabel identicon = new Identicon(walletEntry.getIdenticonHash());
		GridBagConstraints gbc_btnNewButton = new GridBagConstraints();
		gbc_btnNewButton.insets = new Insets(0, 0, 5, 5);
		gbc_btnNewButton.gridx = 0;
		gbc_btnNewButton.gridy = 0;
		JPanel idPanel = new JPanel();
		idPanel.setLayout(new GridBagLayout());
		idPanel.add(identicon);
		add(idPanel, BorderLayout.WEST);

		// address field
		JPanel cPanel = new JPanel();
		cPanel.setLayout(new GridLayout(0, 1));
		CodeLabel addressLabel = new CodeLabel(address.toString());
		addressLabel.setFont(Toolkit.MONO_FONT);
		cPanel.add(addressLabel);
		CodeLabel infoLabel = new CodeLabel(getInfoString());
		cPanel.add(infoLabel);
		add(cPanel, BorderLayout.CENTER);

		PeerGUI.getStateModel().addPropertyChangeListener(e -> {
			infoLabel.setText(getInfoString());
		});
	}

	private void resetTooltipTExt(JComponent b) {
		if (walletEntry.isLocked()) {
			b.setToolTipText("Unlock");
		} else {
			b.setToolTipText("Lock");
		}
	}

	private String getInfoString() {
		State s = PeerGUI.getLatestState();
		Long bal=s.getBalance(address);
		return "Balance: " + ((bal==null)?"Null":Text.toFriendlyNumber(s.getBalance(address)));
	}

}
