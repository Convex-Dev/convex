package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.awt.Font;
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
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class KeyGenPanel extends JPanel {

	JTextArea mnemonicArea;
	JPasswordField passArea;
	JTextArea seedArea;
	JTextArea derivationArea;
	JTextArea privateKeyArea;
	JTextArea publicKeyArea;
	
	JSpinner numSpinner;

	JButton addWalletButton = new JButton("Add to wallet");
	
	JPanel formPanel;

	
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
			Blob bipSeed=BIP39.getSeed(words,p);
			seedArea.setText(bipSeed.toHexString());
			ABlob seed=BIP39.seedToEd25519Seed(bipSeed);
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
			privateKeyArea.setText(BIP39.seedToEd25519Seed(b).toHexString());
			generatePublicKeys();
		} catch (Exception ex) {
			privateKeyArea.setText("<invalid BIP39 seed>");
			publicKeyArea.setText("<invalid BIP39 seed>");
			return;
		}
	}
	
	private void updatePath() {
		try {
			String path=derivationArea.getText();
			String[] es=path.substring(1).split("/");
			if (!"m".equals(es[0])) throw new Exception("<Bad derivation path, must start with 'm'>");
			generatePublicKeys();
		} catch (Exception ex) {
			privateKeyArea.setText(ex.getMessage());
			publicKeyArea.setText(ex.getMessage());
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
			mnemonicArea.setText(BIP39.createSecureMnemonic(wc));
			updateMnemonic();
		});
		
		numSpinner = new JSpinner();
		numSpinner.setModel(new SpinnerNumberModel(12, 3, 30, 1));
		actionPanel.add(numSpinner);

		JButton btnNewButton = new JButton("Export...");
		actionPanel.add(btnNewButton);
		
		{ // Button to Normalise Mnemonic string
			JButton btnNormalise = new JButton("Normalise Mnemonic");
			actionPanel.add(btnNormalise);
			btnNormalise.addActionListener(e -> { 
				String s=mnemonicArea.getText();
				mnemonicArea.setText(BIP39.normalise(s));
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

		// Main Key generation form
		formPanel = new JPanel();
		formPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		formPanel.setLayout(new MigLayout("fillx,wrap 2","[fill,min:250][grow,shrink]",""));
		add(formPanel, BorderLayout.NORTH);

		{ // Mnemonic entry box
			addLabel("Mnenomic Phrase");	
			mnemonicArea = new JTextArea();
			mnemonicArea.setWrapStyleWord(true);
			mnemonicArea.setLineWrap(true);
			mnemonicArea.setRows(2);
			mnemonicArea.setFont(HEX_FONT);
			
			formPanel.add(mnemonicArea,"grow, wmin 100"); 
			// wmin override needed to stop JTextArea expanding
			// see: https://stackoverflow.com/questions/9723425/miglayout-shrink-behavior
			mnemonicArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!mnemonicArea.isFocusOwner()) return;
				updateMnemonic();
			}));
		}

		{ // Passphrase entry box
			addLabel("Passphrase");	
			passArea = new JPasswordField();
			passArea.setFont(HEX_FONT);
			formPanel.add(passArea,"w min:300");
			passArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!passArea.isFocusOwner()) return;
				updatePass();
			}));
		}
		
		{
			addLabel("BIP39 Seed");
			seedArea = new JTextArea();
			seedArea.setFont(HEX_FONT);
			seedArea.setColumns(64);
			seedArea.setLineWrap(true);
			seedArea.setWrapStyleWord(false);
			formPanel.add(seedArea,"grow,wmin 100");
			seedArea.setText("(mnemonic not ready)");
			seedArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!seedArea.isFocusOwner()) return;
				updateSeed();
			}));
		}
		
		{
			addLabel("BIP32 Path");
			derivationArea = new JTextArea();
			derivationArea.setFont(HEX_FONT);
			derivationArea.setColumns(64);
			derivationArea.setLineWrap(true);
			derivationArea.setWrapStyleWord(false);
			formPanel.add(derivationArea,"grow,wmin 100");
			derivationArea.setText("m");
			derivationArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!derivationArea.isFocusOwner()) return;
				updatePath();
			}));
		}

		{
			addLabel("Private Ed25519 seed");
			privateKeyArea = new JTextArea();
			privateKeyArea.setFont(HEX_FONT);
			formPanel.add(privateKeyArea,"grow,wmin 100");
			privateKeyArea.setText("(mnemonic not ready)");
			privateKeyArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!privateKeyArea.isFocusOwner()) return;
				updatePrivateKey();
			}));
		}

		{
			addLabel("Ed25519 Public Key");
			publicKeyArea = new JTextArea();
			publicKeyArea.setEditable(false);
			publicKeyArea.setRows(1);
			publicKeyArea.setText("(private key not ready)");
			publicKeyArea.setFont(HEX_FONT);
			formPanel.add(publicKeyArea,"grow,wmin 100");
		}

	}

	/**
	 *  Add a label component to the specified panel
	 * @param panel
	 * @param string
	 */
	private void addLabel(String labelText) {
			JLabel lblMnemonic = new JLabel(labelText);
			formPanel.add(lblMnemonic);
	}


}
