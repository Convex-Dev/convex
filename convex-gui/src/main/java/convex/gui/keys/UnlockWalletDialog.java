package convex.gui.keys;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;

import convex.core.crypto.wallet.AWalletEntry;
import convex.gui.components.Identicon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class UnlockWalletDialog extends JDialog {
	private JPasswordField passwordField;

	private char[] passPhrase = null;

	public static UnlockWalletDialog show(Component parent, AWalletEntry walletEntry) {
		UnlockWalletDialog dialog = new UnlockWalletDialog(walletEntry);
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
		return dialog;
	}

	public char[] getPassPhrase() {
		return passPhrase;
	}

	public UnlockWalletDialog(AWalletEntry walletEntry) {
		this.setIconImage(Toolkit.WARNING.getImage());
		setAlwaysOnTop(true);

		setModalityType(ModalityType.DOCUMENT_MODAL);
		setTitle("Unlock Key");
		setModal(true);

		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new MigLayout("","","[fill]"));
		mainPanel.setBorder(Toolkit.createDialogBorder());
		getContentPane().add(mainPanel, BorderLayout.NORTH);

		Identicon a = new Identicon(walletEntry.getIdenticonData(),Toolkit.IDENTICON_SIZE_LARGE);
		a.setText("0x"+walletEntry.getPublicKey().toChecksumHex());
		mainPanel.add(a,"span");

		// Unlock prompt and password
		JPanel passPanel = new JPanel();
		JLabel lblPassphrase = new JLabel("Unlock Password: ");
		passPanel.add(lblPassphrase);

		passwordField = new JPasswordField();
		passwordField.setFont(new Font("Monospaced", Font.BOLD, 13));
		passwordField.setColumns(40);
		passPanel.add(Toolkit.wrapPasswordField(passwordField));
		mainPanel.add(passPanel);


		// Dialog buttons
		JPanel buttonPanel = new JPanel();
		JButton btnUnlock = new JButton("Unlock");
		buttonPanel.add(btnUnlock);
		btnUnlock.addActionListener(e -> {
			this.passPhrase = passwordField.getPassword();
			close();
		});
		JButton btnCancel = new JButton("Cancel");
		buttonPanel.add(btnCancel);
		btnCancel.addActionListener(e -> close());
		mainPanel.add(buttonPanel, "dock south");

		Action closeAction = new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				close();
			}
		};

		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				"close");
		getRootPane().getActionMap().put("close", closeAction);

		pack(); // set dialog to correct size given contents
		passwordField.requestFocus();
	}

	public void close() {
		passwordField = null;
		setVisible(false);
	}

	/**
	 * Shows a dialog to ask the user to unlock a wallet entry
	 * @param parent Parent component
	 * @param walletEntry Wallet Entry to consider
	 * @return True if unlocked, false otherwise
	 */
	public static boolean offerUnlock(Component parent, AWalletEntry walletEntry) {
		UnlockWalletDialog dialog = UnlockWalletDialog.show(parent,walletEntry);
		char[] passPhrase = dialog.getPassPhrase();
		if (passPhrase!=null) {
			try {
				walletEntry.unlock(passPhrase);
			} catch (Exception e1) {
				JOptionPane.showMessageDialog(parent, "Unable to unlock keypair: " + e1.getMessage(),"Unlock Failed",JOptionPane.WARNING_MESSAGE);
			}
		}
		return !walletEntry.isLocked();
	}

}
