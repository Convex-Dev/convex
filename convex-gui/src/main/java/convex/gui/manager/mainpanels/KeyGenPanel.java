package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.crypto.Mnemonic;
import convex.core.crypto.WalletEntry;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.manager.PeerGUI;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class KeyGenPanel extends JPanel {

	JTextArea mnemonicArea;
	JTextArea privateKeyArea;
	JTextArea publicKeyArea;

	JButton addWalletButton = new JButton("Add to wallet");

	private String hexKeyFormat(String pk) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < (pk.length() / 32); i++) {
			if (i > 0) sb.append('\n');
			for (int j = 0; j < 4; j++) {
				if (j > 0) sb.append(' ');
				int ix = 8 * (j + (i * 4));
				sb.append(pk.substring(ix, ix + 8));
			}
		}
		return sb.toString();
	}

	private void updateMnemonic() {
		String s = mnemonicArea.getText();
		try {
			byte[] bs = Mnemonic.decode(s, 128);
			Blob keyMat=Blob.wrap(bs);
			ABlob seed=keyMat.getContentHash();
			String privateKeyString = seed.toHexString();
			privateKeyArea.setText(privateKeyString);
		} catch (Exception ex) {
			String pks = "<mnemonic not valid>";
			if (s.isBlank()) pks = "<enter valid private key or mnemonic>";
			privateKeyArea.setText(pks);
		}
		updatePublicKeys();
	}

	private void updatePrivateKey() {
		try {
			mnemonicArea.setText("<can't calculate from private key>");
			updatePublicKeys();
		} catch (Exception ex) {
			System.err.println(ex.getMessage());
			return;
		}
	}

	private void updatePublicKeys() {
		String s = privateKeyArea.getText();
		try {
			Blob b = Blob.fromHex(Utils.stripWhiteSpace(s));
			AKeyPair kp = Ed25519KeyPair.create(b.getBytes());
			// String pk=Utils.toHexString(kp.getPrivateKey(),64);
			publicKeyArea.setText(kp.getAccountKey().toChecksumHex());
			addWalletButton.setEnabled(true);
		} catch (Exception ex) {
			publicKeyArea.setText("<enter valid private key>");
			addWalletButton.setEnabled(false);

			return;
		}
	}

	/**
	 * Create the panel.
	 * @param manager 
	 */
	public KeyGenPanel(PeerGUI manager) {
		setLayout(new BorderLayout(0, 0));

		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton btnRecreate = new JButton("Generate");
		actionPanel.add(btnRecreate);
		btnRecreate.addActionListener(e -> {
			mnemonicArea.setText(Mnemonic.createSecureRandom());
			updateMnemonic();
		});

		JButton btnNewButton = new JButton("Export...");
		actionPanel.add(btnNewButton);

		actionPanel.add(addWalletButton);
		addWalletButton.addActionListener(e -> {
			String pks = privateKeyArea.getText();
			pks = Utils.stripWhiteSpace(pks);
			WalletEntry we = WalletEntry.create(null,AKeyPair.create(Utils.hexToBytes(pks)));
			manager.getWalletPanel().addWalletEntry(we);
			manager.switchPanel("Wallet");

		});

		JPanel formPanel = new JPanel();
		formPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		add(formPanel, BorderLayout.NORTH);
		GridBagLayout gbl_formPanel = new GridBagLayout();
		gbl_formPanel.columnWidths = new int[] { 156, 347, 0 };
		gbl_formPanel.rowHeights = new int[] { 22, 0, 0, 0, 0 };
		gbl_formPanel.columnWeights = new double[] { 1.0, 1.0, Double.MIN_VALUE };
		gbl_formPanel.rowWeights = new double[] { 0.0, 1.0, 1.0, 1.0, Double.MIN_VALUE };
		formPanel.setLayout(gbl_formPanel);

		JLabel lblNewLabel = new JLabel("Mnenomic Phrase");
		GridBagConstraints gbc_lblNewLabel = new GridBagConstraints();
		gbc_lblNewLabel.anchor = GridBagConstraints.WEST;
		gbc_lblNewLabel.anchor = GridBagConstraints.WEST;
		gbc_lblNewLabel.insets = new Insets(0, 0, 5, 5);
		gbc_lblNewLabel.gridx = 0;
		gbc_lblNewLabel.gridy = 0;
		formPanel.add(lblNewLabel, gbc_lblNewLabel);

		mnemonicArea = new JTextArea();
		mnemonicArea.setWrapStyleWord(true);
		mnemonicArea.setLineWrap(true);
		mnemonicArea.setRows(3);
		GridBagConstraints gbc_mnemonicArea = new GridBagConstraints();
		gbc_mnemonicArea.fill = GridBagConstraints.HORIZONTAL;
		gbc_mnemonicArea.insets = new Insets(0, 0, 5, 0);
		gbc_mnemonicArea.gridx = 1;
		gbc_mnemonicArea.gridy = 0;
		mnemonicArea.setColumns(32);
		mnemonicArea.setFont(new Font("Monospaced", Font.BOLD, 13));
		formPanel.add(mnemonicArea, gbc_mnemonicArea);
		mnemonicArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
			if (!mnemonicArea.isFocusOwner()) return;
			updateMnemonic();
		}));

		JLabel lblPrivateKey = new JLabel("Private key seed");
		GridBagConstraints gbc_lblPrivateKey = new GridBagConstraints();
		gbc_lblPrivateKey.anchor = GridBagConstraints.WEST;
		gbc_lblPrivateKey.insets = new Insets(0, 0, 5, 5);
		gbc_lblPrivateKey.gridx = 0;
		gbc_lblPrivateKey.gridy = 1;
		formPanel.add(lblPrivateKey, gbc_lblPrivateKey);

		privateKeyArea = new JTextArea();
		privateKeyArea.setFont(new Font("Monospaced", Font.BOLD, 13));
		GridBagConstraints gbc_privateKeyArea = new GridBagConstraints();
		gbc_privateKeyArea.insets = new Insets(0, 0, 5, 0);
		gbc_privateKeyArea.fill = GridBagConstraints.HORIZONTAL;
		gbc_privateKeyArea.gridx = 1;
		gbc_privateKeyArea.gridy = 1;
		formPanel.add(privateKeyArea, gbc_privateKeyArea);
		privateKeyArea.setText("(mnemonic not ready)");
		privateKeyArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
			if (!privateKeyArea.isFocusOwner()) return;
			updatePrivateKey();
		}));

		JLabel lblPublicKey = new JLabel("Public Key");
		GridBagConstraints gbc_lblPublicKey = new GridBagConstraints();
		gbc_lblPublicKey.anchor = GridBagConstraints.WEST;
		gbc_lblPublicKey.insets = new Insets(0, 0, 5, 5);
		gbc_lblPublicKey.gridx = 0;
		gbc_lblPublicKey.gridy = 2;
		formPanel.add(lblPublicKey, gbc_lblPublicKey);

		publicKeyArea = new JTextArea();
		publicKeyArea.setEditable(false);
		publicKeyArea.setRows(4);
		publicKeyArea.setText("(private key not ready)");
		publicKeyArea.setFont(new Font("Monospaced", Font.BOLD, 13));
		GridBagConstraints gbc_publicKeyArea = new GridBagConstraints();
		gbc_publicKeyArea.insets = new Insets(0, 0, 5, 0);
		gbc_publicKeyArea.fill = GridBagConstraints.HORIZONTAL;
		gbc_publicKeyArea.gridx = 1;
		gbc_publicKeyArea.gridy = 2;
		formPanel.add(publicKeyArea, gbc_publicKeyArea);

	}

}
