package convex.gui.components;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import convex.core.State;
import convex.core.crypto.WalletEntry;
import convex.core.data.Address;
import convex.core.util.Text;
import convex.gui.manager.PeerGUI;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class WalletComponent extends BaseListComponent {
	Icon icon = Toolkit.LOCKED_ICON;

	JButton lockButton;

	WalletEntry walletEntry;

	JPanel buttons = new JPanel();

	private Address address;

	public WalletComponent(WalletEntry initialWalletEntry) {
		this.walletEntry = initialWalletEntry;
		address = walletEntry.getAddress();

		setLayout(new BorderLayout());

		// lock button
		lockButton = new JButton("");
		buttons.add(lockButton);
		lockButton.setIcon(walletEntry.isLocked() ? Toolkit.LOCKED_ICON : Toolkit.UNLOCKED_ICON);
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
			lockButton.setIcon(icon);
		});

		// panel of buttons on right
		add(buttons, BorderLayout.EAST);

		// identicon
		JLabel identicon = new Identicon(walletEntry.getAddress().getHash());
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

	private String getInfoString() {
		State s = PeerGUI.getLatestState();
		Long bal=s.getBalance(address);
		return "Balance: " + ((bal==null)?"Null":Text.toFriendlyBalance(s.getBalance(address)));
	}

}
