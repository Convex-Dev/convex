package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.WalletEntry;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.util.Utils;
import convex.gui.PeerGUI;
import convex.gui.components.ActionPanel;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class KeyGenPanel extends JPanel {

	JTextArea mnemonicArea;
	JPasswordField passArea;
	JTextArea seedArea;
	JTextArea privateKeyArea;
	JTextArea publicKeyArea;
	
	JSpinner numSpinner;


	JButton addWalletButton = new JButton("Add to wallet");
	
	int FONT_SIZE=16;
	Font HEX_FONT=new Font("Monospaced", Font.BOLD, FONT_SIZE);

	/** 
	 * Format a hex string in blocks for digits
	 * @param pk
	 * @return
	 */
	protected String hexKeyFormat(String pk) {
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
		generateSeed();
	}
	

	private void updatePass() {
		generateSeed();
	}
	
	private void generateSeed() {
		String s = mnemonicArea.getText();
		String p = new String(passArea.getPassword());
		try {
			List<String> words=BIP39.getWords(s);
			Blob keyMat=BIP39.getSeed(words,p);
			seedArea.setText(keyMat.toHexString());
			ABlob seed=keyMat.getContentHash();
			String privateKeyString = seed.toHexString();
			privateKeyArea.setText(privateKeyString);
		} catch (Exception ex) {
			String pks = "<mnemonic not valid>";
			if (s.isBlank()) pks = "<enter valid private key or mnemonic>";
			privateKeyArea.setText(pks);
		}		
		generatePublicKeys();
	}
	
	private void updateSeed() {
		try {
			mnemonicArea.setText("<can't recreate from BIP39 seed>");
			Blob b=Blobs.parse(seedArea.getText()).toFlatBlob(); 
			if ((b==null)||(b.count()!=BIP39.SEED_LENGTH)) throw new IllegalArgumentException("Dummy");
			privateKeyArea.setText(b.getContentHash().toHexString());
			generatePublicKeys();
		} catch (Exception ex) {
			privateKeyArea.setText("<invalid BIP39 seed>");
			publicKeyArea.setText("<invalid BIP39 seed>");
			return;
		}
	}

	private void updatePrivateKey() {
		try {
			mnemonicArea.setText("<can't recreate from private key>");
			seedArea.setText("<can't recreate from private key>");
			generatePublicKeys();
		} catch (Exception ex) {
			System.err.println(ex.getMessage());
			return;
		}
	}

	private void generatePublicKeys() {
		String s = privateKeyArea.getText();
		try {
			Blob b = Blob.fromHex(Utils.stripWhiteSpace(s));
			AKeyPair kp = AKeyPair.create(b.getBytes());
			// String pk=Utils.toHexString(kp.getPrivateKey(),64);
			publicKeyArea.setText("0x"+kp.getAccountKey().toChecksumHex());
			addWalletButton.setEnabled(true);
		} catch (Exception ex) {
			publicKeyArea.setText("<enter valid private key>");
			addWalletButton.setEnabled(false);
			return;
		}
	}

	/**
	 * Create the panel.
	 * @param manager GUI manager root component
	 */
	public KeyGenPanel(PeerGUI manager) {
		setLayout(new BorderLayout(0, 0));

		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton btnRecreate = new JButton("Generate");
		actionPanel.add(btnRecreate);
		btnRecreate.addActionListener(e -> {
			Integer wc=(Integer) numSpinner.getValue();
			mnemonicArea.setText(BIP39.createSecureRandom(wc));
			updateMnemonic();
		});
		
		numSpinner = new JSpinner();
		numSpinner.setModel(new SpinnerNumberModel(12, 1, 30, 1));
		actionPanel.add(numSpinner);

		JButton btnNewButton = new JButton("Export...");
		actionPanel.add(btnNewButton);
		
		{ // Button to Normalise Mnemonic string
			JButton btnNormalise = new JButton("Normalise Mnemonic");
			actionPanel.add(btnNormalise);
			btnNormalise.addActionListener(e -> { 
				String s=mnemonicArea.getText();
				mnemonicArea.setText(BIP39.normaliseSpaces(s));
				updateMnemonic();
			});
		}


		actionPanel.add(addWalletButton);
		addWalletButton.addActionListener(e -> {
			String pks = privateKeyArea.getText();
			pks = Utils.stripWhiteSpace(pks);
			WalletEntry we = WalletEntry.create(null,AKeyPair.create(Utils.hexToBytes(pks)));
			WalletPanel.addWalletEntry(we);
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

		{ // Mnemonic label
			JLabel lblMnemonic = new JLabel("Mnenomic Phrase");
			GridBagConstraints gbc_lblNewLabel = new GridBagConstraints();
			gbc_lblNewLabel.anchor = GridBagConstraints.WEST;
			gbc_lblNewLabel.insets = new Insets(0, 0, 5, 5);
			gbc_lblNewLabel.gridx = 0;
			gbc_lblNewLabel.gridy = 0;
			formPanel.add(lblMnemonic, gbc_lblNewLabel);
		}

		{ // Mnemonic entry box
			mnemonicArea = new JTextArea();
			mnemonicArea.setWrapStyleWord(true);
			mnemonicArea.setLineWrap(true);
			mnemonicArea.setRows(2);
			GridBagConstraints gbc_mnemonicArea = new GridBagConstraints();
			gbc_mnemonicArea.fill = GridBagConstraints.HORIZONTAL;
			gbc_mnemonicArea.insets = new Insets(0, 0, 5, 0);
			gbc_mnemonicArea.gridx = 1;
			gbc_mnemonicArea.gridy = 0;
			mnemonicArea.setColumns(32);
			mnemonicArea.setFont(HEX_FONT);
			formPanel.add(mnemonicArea, gbc_mnemonicArea);
			mnemonicArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!mnemonicArea.isFocusOwner()) return;
				updateMnemonic();
			}));
		}

		{ // Passphrase label
			JLabel lblPass = new JLabel("Passphrase");
			GridBagConstraints gbc_lblNewLabel = new GridBagConstraints();
			gbc_lblNewLabel.anchor = GridBagConstraints.WEST;
			gbc_lblNewLabel.insets = new Insets(0, 0, 5, 5);
			gbc_lblNewLabel.gridx = 0;
			gbc_lblNewLabel.gridy = 1;
			formPanel.add(lblPass, gbc_lblNewLabel);
		}

		{ // Passphrase entry box
			passArea = new JPasswordField();
			GridBagConstraints gbc_mnemonicArea = new GridBagConstraints();
			gbc_mnemonicArea.fill = GridBagConstraints.HORIZONTAL;
			gbc_mnemonicArea.insets = new Insets(0, 0, 5, 0);
			gbc_mnemonicArea.gridx = 1;
			gbc_mnemonicArea.gridy = 1;
			passArea.setColumns(32);
			passArea.setFont(HEX_FONT);
			formPanel.add(passArea, gbc_mnemonicArea);
			passArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!passArea.isFocusOwner()) return;
				updatePass();
			}));
		}
		
		{
			JLabel lblBIPSeed = new JLabel("BIP39 Seed");
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.anchor = GridBagConstraints.WEST;
			gbc.insets = new Insets(0, 0, 5, 5);
			gbc.gridx = 0;
			gbc.gridy = 2;
			formPanel.add(lblBIPSeed, gbc);
		}
		
		{
			seedArea = new JTextArea();
			seedArea.setFont(HEX_FONT);
			seedArea.setColumns(64);
			seedArea.setLineWrap(true);
			seedArea.setWrapStyleWord(false);
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(0, 0, 5, 0);
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.gridx = 1;
			gbc.gridy = 2;
			formPanel.add(seedArea, gbc);
			seedArea.setText("(mnemonic not ready)");
			seedArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!seedArea.isFocusOwner()) return;
				updateSeed();
			}));
		}

		{
			JLabel lblPrivateKey = new JLabel("Private key Ed25519 seed");
			GridBagConstraints gbc_lblPrivateKey = new GridBagConstraints();
			gbc_lblPrivateKey.anchor = GridBagConstraints.WEST;
			gbc_lblPrivateKey.insets = new Insets(0, 0, 5, 5);
			gbc_lblPrivateKey.gridx = 0;
			gbc_lblPrivateKey.gridy = 3;
			formPanel.add(lblPrivateKey, gbc_lblPrivateKey);
		}


		privateKeyArea = new JTextArea();
		privateKeyArea.setFont(HEX_FONT);
		GridBagConstraints gbc_privateKeyArea = new GridBagConstraints();
		gbc_privateKeyArea.insets = new Insets(0, 0, 5, 0);
		gbc_privateKeyArea.fill = GridBagConstraints.HORIZONTAL;
		gbc_privateKeyArea.gridx = 1;
		gbc_privateKeyArea.gridy = 3;
		formPanel.add(privateKeyArea, gbc_privateKeyArea);
		privateKeyArea.setText("(mnemonic not ready)");
		privateKeyArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
			if (!privateKeyArea.isFocusOwner()) return;
			updatePrivateKey();
		}));

		{
			JLabel lblPublicKey = new JLabel("Public Key");
			GridBagConstraints gbc_lblPublicKey = new GridBagConstraints();
			gbc_lblPublicKey.anchor = GridBagConstraints.WEST;
			gbc_lblPublicKey.insets = new Insets(0, 0, 5, 5);
			gbc_lblPublicKey.gridx = 0;
			gbc_lblPublicKey.gridy = 4;
			formPanel.add(lblPublicKey, gbc_lblPublicKey);
		}

		{
			publicKeyArea = new JTextArea();
			publicKeyArea.setEditable(false);
			publicKeyArea.setRows(1);
			publicKeyArea.setText("(private key not ready)");
			publicKeyArea.setFont(HEX_FONT);
			GridBagConstraints gbc_publicKeyArea = new GridBagConstraints();
			gbc_publicKeyArea.insets = new Insets(0, 0, 5, 0);
			gbc_publicKeyArea.fill = GridBagConstraints.HORIZONTAL;
			gbc_publicKeyArea.gridx = 1;
			gbc_publicKeyArea.gridy = 4;
			formPanel.add(publicKeyArea, gbc_publicKeyArea);
		}

	}


}
