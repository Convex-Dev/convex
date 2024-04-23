package convex.gui.keys;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;

import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class UnlockWalletDialog extends JDialog {
	private JPasswordField passwordField;

	private char[] passPhrase = null;

	public static UnlockWalletDialog show(WalletComponent parent) {
		UnlockWalletDialog dialog = new UnlockWalletDialog(parent);
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
		return dialog;
	}

	public char[] getPassPhrase() {
		return passPhrase;
	}

	public UnlockWalletDialog(WalletComponent walletComponent) {
		this.setIconImage(Toolkit.WARNING.getImage());
		setAlwaysOnTop(true);

		setModalityType(ModalityType.DOCUMENT_MODAL);
		setTitle("Unlock Wallet");
		setModal(true);

		JPanel panel_2 = new JPanel();
		getContentPane().add(panel_2, BorderLayout.NORTH);
		panel_2.setLayout(new BorderLayout(0, 0));

		JPanel panel_1 = new JPanel();
		panel_2.add(panel_1, BorderLayout.SOUTH);

		JButton btnUnlock = new JButton("Unlock");
		panel_1.add(btnUnlock);
		btnUnlock.addActionListener(e -> {
			this.passPhrase = passwordField.getPassword();
			close();
		});
		JButton btnCancel = new JButton("Cancel");
		panel_1.add(btnCancel);

		JPanel panel = new JPanel();
		panel_2.add(panel);

		JLabel lblPassphrase = new JLabel("Password: ");
		panel.add(lblPassphrase);

		passwordField = new JPasswordField();
		passwordField.setFont(new Font("Monospaced", Font.BOLD, 13));
		passwordField.setColumns(20);
		panel.add(passwordField);
		btnCancel.addActionListener(e -> close());

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

	}

	public void close() {
		passwordField = null;
		setVisible(false);
	}

}
