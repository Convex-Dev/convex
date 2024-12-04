package convex.gui.keys;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;

import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.Passwords;
import convex.core.crypto.SLIP10;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.Identicon;
import convex.gui.components.RightCopyMenu;
import convex.gui.peer.PeerGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class KeyGenPanel extends JPanel {

	private static final String NOTE_CONSTRAINT = "align 50%,span,width 100:600:1000";
	private static final String TEXTAREA_CONSTRAINT = "grow,width 10:500:800";
	JTextArea mnemonicArea;
	JPasswordField passArea;
	JTextArea seedArea;
	
	JTextArea warningArea;
	
	JTextArea masterKeyArea;
	JTextArea derivationArea;
	JTextArea derivedKeyArea;
	JTextArea privateKeyArea;
	JTextArea publicKeyArea;
	
	JSpinner numSpinner;

	ActionButton addWalletButton;
	
	JPanel formPanel;
	
	static Font HEX_FONT=Toolkit.MONO_FONT;

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
		generateBIP39Seed();
	}
	

	private void updatePass() {
		generateBIP39Seed();
	}
	
	private void generateBIP39Seed() {
		String s = mnemonicArea.getText();
		String p = new String(passArea.getPassword());

		String warn=checkWarnings(s,p);

		if (warn.isBlank()) {
			warningArea.setForeground(Color.GREEN);
			warningArea.setText("OK: Reasonable mnemonic and passphrase");
		} else {
			warningArea.setForeground(Color.ORANGE);
			warningArea.setText("WARNING: "+warn);
		}
		
		try {
			Blob bipSeed=BIP39.getSeed(s, p);
			seedArea.setText(bipSeed.toHexString());
			deriveSeed();
		} catch (Exception ex) {
			String pks = "<mnemonic not valid>";
			if (s.isBlank()) pks = "<enter valid private key or mnemonic>";
			masterKeyArea.setText(pks);
			warningArea.setText("");
			derivedKeyArea.setText(pks);
			privateKeyArea.setText(pks);
		}		
	}
	
	private String checkWarnings(String s, String p) {
		String warn ="";

		// Check for demo phrases
		if (s.equals(BIP39.DEMO_PHRASE)) warn+= "Demo mnemonic (for testing only). ";
		if (p.equals(BIP39.DEMO_PASS)) warn+= "Demo passphrase (for testing only). ";

		List<String> words=BIP39.getWords(s);
		String badWord=BIP39.checkWords(words);
		
		int numWords=words.size();
		if (numWords<12) {
			warn+="Only "+numWords+" words. ";
		} else if ((numWords!=((numWords/3)*3)) || numWords>24) {
			warn+="Unusual number of words ("+numWords+"). ";
		} else if (badWord!=null) {
			if (BIP39.extendWord(badWord)!=null) {
				warn += "Should normalise abbreviated word: "+badWord+". ";
			} else {
				warn +="Not in standard word list: "+badWord+". ";
			}
		} else if (!s.equals(BIP39.normaliseFormat(s))) {
			warn+="Not normalised! ";
		} else if (!BIP39.checkSum(s)) {
			warn+="Invalid checksum! ";		
		}
		
		if (p.isBlank()) {
			warn+="Passphrase is blank! ";
		} else {
			int entropy=Passwords.estimateEntropy(p);
			if (entropy<10) {
				warn+="Very weak passphrase! ";
			} else if (entropy<20) {
				warn+="Weak passphrase. ";
			} else if (entropy<30) {
				warn+="Moderate passphrase. ";
			}
		}

		return warn;
	}

	private void updatePath() {
		try {
			String path=derivationArea.getText();
			this.derivationPath=BIP39.parsePath(path);
			deriveSeed();
		} catch (Exception ex) {
			privateKeyArea.setText(ex.getMessage());
			publicKeyArea.setText(ex.getMessage());
			derivationPath=null;
			return;
		}
	}
	
	private void updateSeed() {
		mnemonicArea.setText("<can't recreate from BIP39 seed>");
		deriveSeed();
	}
	
	private void deriveSeed() {
		try {
			Blob b=Blobs.parse(seedArea.getText()).toFlatBlob(); 
			if (b==null) throw new IllegalArgumentException("<invalid BIP39 seed>");
			
			Blob mb=SLIP10.getMaster(b);
			masterKeyArea.setText(mb.toHexString());
			Blob db;
			this.derivationPath=BIP39.parsePath(derivationArea.getText());
			if (derivationPath==null) {
				db=mb;
			} else {
				db=SLIP10.derive(mb, derivationPath);
			}
			derivedKeyArea.setText(db.toHexString());
			
			privateKeyArea.setText(db.slice(0,32).toHexString());
			generatePublicKey();
		} catch (Exception ex) {
			privateKeyArea.setText(ex.getMessage());
			publicKeyArea.setText(ex.getMessage());
			return;
		}
	}
	
	int[] derivationPath=null;
	private Identicon identicon;
	


	private void updatePrivateKey() {
		try {
			String msg="<can't recreate from private seed>";
			mnemonicArea.setText(msg);
			seedArea.setText(msg);
			masterKeyArea.setText(msg);
			derivedKeyArea.setText(msg);
			generatePublicKey();
		} catch (Exception ex) {
			System.err.println(ex.getMessage());
			return;
		}
	}

	private void generatePublicKey() {
		String s = privateKeyArea.getText().trim();
		try {
			if (s.startsWith("0x")) s=s.substring(2);
			Blob b = Blob.fromHex(Utils.stripWhiteSpace(s));
			AKeyPair kp = AKeyPair.create(b.getBytes());
			
			AccountKey publicKey=kp.getAccountKey();
			// String pk=Utils.toHexString(kp.getPrivateKey(),64);
			publicKeyArea.setText("0x"+publicKey.toChecksumHex());
			addWalletButton.setEnabled(true);
			
			identicon.setKey(publicKey);
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
		setLayout(new BorderLayout());

		// Main Key generation form
		
		formPanel = new JPanel();
		// formPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		formPanel.setLayout(new MigLayout("wrap 3","[][40]10[grow,fill]",""));
		add(formPanel, BorderLayout.CENTER);

		{ // Mnemonic entry box
			addLabel("Mnemonic Phrase","BIP39 Mnemonic phrase. These should be 12, 15, 18, 21 or 24 random words from the BIP39 standard word list.");	
			mnemonicArea = makeTextArea();
			mnemonicArea.setWrapStyleWord(true);
			mnemonicArea.setLineWrap(true);
			mnemonicArea.setRows(2);
			mnemonicArea.setBackground(Color.BLACK);
			
			formPanel.add(mnemonicArea,TEXTAREA_CONSTRAINT); 
			// wmin override needed to stop JTextArea expanding
			// see: https://stackoverflow.com/questions/9723425/miglayout-shrink-behavior
			mnemonicArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!mnemonicArea.isFocusOwner()) return;
				updateMnemonic();
			}));
		}

		{ // Passphrase entry box
			addLabel("Passphrase","BIP39 secret passphrase. This acts as a secret 'extra word' to generate the BIP39 seed alongside the mnemonic. Strong passphrase recommended.");	
			passArea = new JPasswordField();
			passArea.setBackground(Color.BLACK);
			formPanel.add(passArea,"grow,width 10:300:400");
			passArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!passArea.isFocusOwner()) return;
				updatePass();
			}));
		}
		
		{
			addLabel("BIP39 Seed","This is the BIP39 seed generated from the mnemonic and passphrase. You can also enter this directly.");
			seedArea = makeTextArea();
			seedArea.setRows(2);
			seedArea.setLineWrap(true);
			seedArea.setWrapStyleWord(false);
			seedArea.setBackground(Color.BLACK);
			formPanel.add(seedArea,TEXTAREA_CONSTRAINT);
			seedArea.setText("(mnemonic not ready)");
			seedArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!seedArea.isFocusOwner()) return;
				updateSeed();
			}));
		}
		
	    {
	    	formPanel.add(new JPanel()); // skip first 2 columns
	    	formPanel.add(new JPanel()); // skip first 2 columns
			warningArea = makeTextArea();

			warningArea.setLineWrap(true);
			warningArea.setWrapStyleWord(true);
			warningArea.setEditable(false);
			warningArea.setToolTipText("This is a quick heuristic check of mnemonic and passphrase.\nHeeding any warnings is advised, but you can ignore them if you know what you are doing (or don't care).");
			formPanel.add(warningArea,TEXTAREA_CONSTRAINT);			
	    }
	
		
		addNote("Once the BIP39 seed is generated, we use SLIP-10 to create a derivation path to an Ed25519 private key. \n\nInstead of a BIP39 seed, you can also use another good secret source of random entropy, e.g. SLIP-0039.");
		
		{
			addLabel("SLIP-10 Master Key","SLIP-10 creates a Master Key from the BIP39 seed, which acts as the root of key generation for a heirarchical deterministic wallet.");
			masterKeyArea = makeTextArea();

			masterKeyArea.setLineWrap(true);
			masterKeyArea.setWrapStyleWord(false);
			masterKeyArea.setEditable(false);
			formPanel.add(masterKeyArea,TEXTAREA_CONSTRAINT);
			masterKeyArea.setText("(not ready)");
		}
		
		{
			int CC = Constants.CHAIN_CODE;
			addLabel("BIP32 Path","This is the hierarchical path for key generation as defined in BIP32. \n - 'm' specifies the master key. \n - 44 is the purpose defined as BIP-44. \n - "+CC+" is the Convex SLIP-0044 chain code. \n - The other numbers (account, change, index) may be set at the user's discretion, but are zero by default. \n\nIf you want multiple keys for the same mnemomic, is is recommended to increment the account number. \n\nWARNING: if you change these values, you will need to retain them in order to recover the specified key.");
			derivationArea = makeTextArea();

			derivationArea.setLineWrap(true);
			derivationArea.setWrapStyleWord(false);
			derivationArea.setBackground(Color.BLACK);

			formPanel.add(derivationArea,TEXTAREA_CONSTRAINT);
			derivationArea.setText("m/44/"+CC+"/0/0/0");
			derivationArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!derivationArea.isFocusOwner()) return;
				updatePath();
			}));
		}
		
		{
			addLabel("SLIP-10 Ext. Priv. Key","This is the extended private key produced by SLIP-10 after applying the BIP32 derivation path. \nThe first 32 bytes of the SLIP-10 extended private key are used as the Ed25519 seed.");
			derivedKeyArea = makeTextArea();
			derivedKeyArea.setLineWrap(true);
			derivedKeyArea.setWrapStyleWord(false);
			derivedKeyArea.setEditable(false);
			formPanel.add(derivedKeyArea,TEXTAREA_CONSTRAINT);
			derivedKeyArea.setText("(not ready)");
		}

		{
			addLabel("Private Ed25519 seed","This is the Ed25519 private seed you need to sign transactions in Convex. \nAny 32-byte hex value will work: you can enter this directly if you obtained a good secret random seed from another source.");
			privateKeyArea = makeTextArea();
			privateKeyArea.setBackground(Color.BLACK);

			formPanel.add(privateKeyArea,TEXTAREA_CONSTRAINT);
			privateKeyArea.setText("(mnemonic not ready)");
			privateKeyArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!privateKeyArea.isFocusOwner()) return;
				updatePrivateKey();
			}));
		}

		{
			addLabel("Ed25519 Public Key","This is the Ed25519 public key, which can be shared publicly and may be used as the account key for a Convex account.");
			publicKeyArea = makeTextArea();
			publicKeyArea.setEditable(false);
			publicKeyArea.setRows(1);
			publicKeyArea.setText("(private key not ready)");
			publicKeyArea.setFont(HEX_FONT);
			formPanel.add(publicKeyArea,TEXTAREA_CONSTRAINT);
		}
		
		identicon=new Identicon(null,Toolkit.IDENTICON_SIZE_LARGE);
		// identicon.setBorder(null);
		addLabel("Identicon","This is a visual representation of the public key. It can be used to visually identify different keys.");
		formPanel.add(identicon,"grow 0");

		////////////////////////////////////////////////////////////////
		// Action panel with buttons 
		
		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton btnRecreate = new ActionButton("Generate",0xe5d5,e -> {
			Integer wc=(Integer) numSpinner.getValue();
			mnemonicArea.setText(BIP39.createSecureMnemonic(wc));
			updateMnemonic();
		});
		btnRecreate.setToolTipText("Press to generate a new random mnemonic and derive all subsequent keys.");
		actionPanel.add(btnRecreate);
		
		numSpinner = new JSpinner();
		numSpinner.setModel(new SpinnerNumberModel(12, 12, 24, 3));
		numSpinner.setEditor(new JSpinner.DefaultEditor(numSpinner));
		numSpinner.setFocusable(false);
		actionPanel.add(numSpinner);

		JButton btnNewButton = new ActionButton("Export...",0xebbe,e->{
			
		});
		actionPanel.add(btnNewButton);
		
		{ // Button to Normalise Mnemonic string
			JButton btnNormalise = new ActionButton("Normalise Mnemonic",0xf0ff,e -> { 
				String s=mnemonicArea.getText();
				String s2=BIP39.normaliseAll(s);
				mnemonicArea.setText(s2);
				updateMnemonic();
			});
			btnNormalise.setToolTipText("Press to normalise mnemonic text according to BIP39. Removes irregular whitespace, sets characters to lowercase.");
			actionPanel.add(btnNormalise);
		}

		addWalletButton=new ActionButton("Add to keyring",0xe145,e -> {
			String pks = privateKeyArea.getText();
			pks = Utils.stripWhiteSpace(pks);
			HotWalletEntry we = HotWalletEntry.create(AKeyPair.create(Utils.hexToBytes(pks)), "Generated via KeyGen");
			KeyRingPanel.addWalletEntry(we);
			if (manager!=null) manager.switchPanel("Keyring");
		});		
		addWalletButton.setToolTipText("Press to add this public / private key pair to the Keyring.");
		actionPanel.add(addWalletButton);
		addWalletButton.setEnabled(false);
	
	}

	private void addNote(String s) {
		JComponent ta = Toolkit.makeNote("NOTE",s);
		formPanel.add(ta,NOTE_CONSTRAINT);
	}

	private JTextArea makeTextArea() {
		JTextArea ta= new JTextArea(0,64);
		ta.setFont(HEX_FONT);
		RightCopyMenu.addTo(ta);

		// ta.setMinimumSize(new Dimension(10,10));
		return ta;
	}

	/**
	 *  Add a label component to the specified panel
	 * @param panel
	 * @param string
	 */
	private void addLabel(String labelText,String helpText) {
		JLabel lblMnemonic = new JLabel(labelText);
		formPanel.add(lblMnemonic);
		formPanel.add(Toolkit.makeHelp(helpText));
	}


}
