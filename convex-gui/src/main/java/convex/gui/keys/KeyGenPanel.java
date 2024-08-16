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

import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
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

	private static final String NOTE_CONSTRAINT = "align 50%,span 2,width 100:600:1000";
	private static final String TEXTAREA_CONSTRAINT = "grow,width 10:500:800";
	JTextArea mnemonicArea;
	JPasswordField passArea;
	JTextArea seedArea;
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
			deriveSeed();
		} catch (Exception ex) {
			String pks = "<mnemonic not valid>";
			if (s.isBlank()) pks = "<enter valid private key or mnemonic>";
			masterKeyArea.setText(pks);
			derivedKeyArea.setText(pks);
			privateKeyArea.setText(pks);
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
	
	private void updatePath() {
		try {
			String path=derivationArea.getText();
			String[] es=path.split("/");
			if (!"m".equals(es[0])) throw new Exception("<Bad derivation path, must start with 'm'>");
			
			int n=es.length-1;
			int[] proposedPath=new int[n];
			for (int i=0; i<n; i++) {
				try {
					Integer ix= Integer.parseInt(es[i+1]);
					proposedPath[i]=ix;
				} catch (NumberFormatException e) {
					throw new Exception("<Bad derivation path, should be integer indexes 'm/44/888/1/0/123' >");
				}
			}
			this.derivationPath=proposedPath;
			updateSeed();
		} catch (Exception ex) {
			privateKeyArea.setText(ex.getMessage());
			publicKeyArea.setText(ex.getMessage());
			derivationPath=null;
			return;
		}
	}

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
		formPanel.setLayout(new MigLayout("wrap 2","[]10[grow,fill]",""));
		add(formPanel, BorderLayout.CENTER);

		{ // Mnemonic entry box
			addLabel("Mnenomic Phrase");	
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
			addLabel("Passphrase");	
			passArea = new JPasswordField();
			passArea.setBackground(Color.BLACK);
			formPanel.add(passArea,"grow,width 10:300:400");
			passArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!passArea.isFocusOwner()) return;
				updatePass();
			}));
		}
		
		{
			addLabel("BIP39 Seed");
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
		
		addNote("Once the BIP39 seed is generated, we use SLIP-10 to create a derivation path to an Ed25519 private key. Instead of a BIP39 seed, you can also use another good secret source of random entropy, e.g. SLIP-0039.");
		
		{
			addLabel("SLIP-10 Master Key");
			masterKeyArea = makeTextArea();

			masterKeyArea.setLineWrap(true);
			masterKeyArea.setWrapStyleWord(false);
			masterKeyArea.setEditable(false);
			formPanel.add(masterKeyArea,TEXTAREA_CONSTRAINT);
			masterKeyArea.setText("(not ready)");
		}
		
		{
			addLabel("BIP32 Path");
			derivationArea = makeTextArea();

			derivationArea.setLineWrap(true);
			derivationArea.setWrapStyleWord(false);
			derivationArea.setBackground(Color.BLACK);

			formPanel.add(derivationArea,TEXTAREA_CONSTRAINT);
			derivationArea.setText("m");
			derivationArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> {
				if (!derivationArea.isFocusOwner()) return;
				updatePath();
			}));
		}
		
		{
			addLabel("SLIP-10 Ext. Priv. Key");
			derivedKeyArea = makeTextArea();
			derivedKeyArea.setLineWrap(true);
			derivedKeyArea.setWrapStyleWord(false);
			derivedKeyArea.setEditable(false);
			formPanel.add(derivedKeyArea,TEXTAREA_CONSTRAINT);
			derivedKeyArea.setText("(not ready)");
		}
		
		addNote("The first 32 bytes of the SLIP-10 extended private key are used as the Ed25519 seed. This is all you strictly need to sign transactions in Convex. Any 32-byte hex value will work: you can enter this directly if you obtained a good secret random seed from another source.");


		{
			addLabel("Private Ed25519 seed");
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
			addLabel("Ed25519 Public Key");
			publicKeyArea = makeTextArea();
			publicKeyArea.setEditable(false);
			publicKeyArea.setRows(1);
			publicKeyArea.setText("(private key not ready)");
			publicKeyArea.setFont(HEX_FONT);
			formPanel.add(publicKeyArea,TEXTAREA_CONSTRAINT);
		}
		
		identicon=new Identicon(null);
		// identicon.setBorder(null);
		addLabel("Identicon:");
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
		actionPanel.add(btnRecreate);
		
		numSpinner = new JSpinner();
		numSpinner.setModel(new SpinnerNumberModel(12, 3, 30, 1));
		actionPanel.add(numSpinner);

		JButton btnNewButton = new ActionButton("Export...",0xebbe,e->{
			
		});
		actionPanel.add(btnNewButton);
		
		{ // Button to Normalise Mnemonic string
			JButton btnNormalise = new ActionButton("Normalise Mnemonic",0xf0ff,e -> { 
				String s=mnemonicArea.getText();
				mnemonicArea.setText(BIP39.normalise(s));
				updateMnemonic();
			});
			actionPanel.add(btnNormalise);
		}

		addWalletButton=new ActionButton("Add to keyring",0xe145,e -> {
			String pks = privateKeyArea.getText();
			pks = Utils.stripWhiteSpace(pks);
			HotWalletEntry we = HotWalletEntry.create(AKeyPair.create(Utils.hexToBytes(pks)));
			KeyRingPanel.addWalletEntry(we);
			if (manager!=null) manager.switchPanel("Keyring");
		});		
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
	private void addLabel(String labelText) {
		JLabel lblMnemonic = new JLabel(labelText);
		formPanel.add(lblMnemonic);
	}


}
