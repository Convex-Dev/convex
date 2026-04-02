package convex.gui.tools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.charset.StandardCharsets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Ref;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.CodeLabel;
import convex.gui.components.account.KeyPairCombo;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Panel for signing and verifying arbitrary data using Ed25519 keys.
 *
 * <p>Supports three input formats:
 * <ul>
 *   <li><b>Hex</b> — raw bytes as a hex string</li>
 *   <li><b>UTF-8 Text</b> — arbitrary text, signed as its UTF-8 byte encoding</li>
 *   <li><b>CVX Data</b> — Convex Lisp value, signed as its ref encoding
 *       (matching the behaviour of {@link convex.core.data.SignedData})</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class SignaturePanel extends JPanel {

	private static final String FORMAT_HEX = "Hex";
	private static final String FORMAT_UTF8 = "UTF-8 Text";
	private static final String FORMAT_CVX = "CVX Data";

	private KeyPairCombo keyCombo;
	private JTextArea dataArea;
	private JComboBox<String> formatCombo;
	private CodeLabel signatureArea;
	private JTextArea infoArea;
	private ActionButton signButton;
	private ActionButton verifyButton;

	public SignaturePanel() {
		setLayout(new BorderLayout(0, 0));

		// Instructions
		JPanel instructionsPanel = new JPanel();
		add(instructionsPanel, BorderLayout.NORTH);
		instructionsPanel.add(new JLabel("Sign and verify arbitrary data with Ed25519 keys"));

		// Main split pane
		JSplitPane splitPane = new JSplitPane();
		splitPane.setOneTouchExpandable(true);
		splitPane.setResizeWeight(0.5);
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		// Top panel — input
		JPanel inputPanel = new JPanel();
		inputPanel.setLayout(new MigLayout("fillx, insets 10", "[][grow]", "[]10[]10[grow]"));

		JLabel keyLabel = new JLabel("Key:");
		inputPanel.add(keyLabel);
		keyCombo = KeyPairCombo.create();
		inputPanel.add(keyCombo, "growx, wrap");

		JLabel formatLabel = new JLabel("Format:");
		inputPanel.add(formatLabel);
		formatCombo = new JComboBox<>(new String[] { FORMAT_HEX, FORMAT_UTF8, FORMAT_CVX });
		formatCombo.addActionListener(e -> updateTooltip());
		inputPanel.add(formatCombo, "growx, wrap");

		JLabel dataLabel = new JLabel("Data:");
		inputPanel.add(dataLabel, "top");
		dataArea = new JTextArea();
		dataArea.setFont(Toolkit.MONO_FONT);
		dataArea.setLineWrap(true);
		dataArea.setRows(6);
		updateTooltip();
		JScrollPane dataScroll = new JScrollPane(dataArea);
		inputPanel.add(dataScroll, "grow, wrap");

		// Sign / Verify buttons
		JPanel buttonRow = new JPanel();
		signButton = new ActionButton("Sign", 0xe5ca, new SignAction());
		signButton.setToolTipText("Sign the data with the selected key");
		buttonRow.add(signButton);

		verifyButton = new ActionButton("Verify", 0xe8e8, new VerifyAction());
		verifyButton.setToolTipText("Verify the signature against the selected key and data");
		buttonRow.add(verifyButton);

		inputPanel.add(buttonRow, "span 2, center, wrap");

		splitPane.setLeftComponent(inputPanel);

		// Bottom panel — output
		JPanel outputPanel = new JPanel();
		outputPanel.setLayout(new BorderLayout(0, 0));

		signatureArea = new CodeLabel();
		signatureArea.setEditable(true);
		signatureArea.setToolTipText("Ed25519 signature (hex). Paste a signature here to verify.");
		signatureArea.setFont(Toolkit.MONO_FONT);
		signatureArea.setBackground(Color.BLACK);
		signatureArea.setLineWrap(true);
		signatureArea.setMaxColumns(65);
		JScrollPane sigScroll = new JScrollPane(signatureArea);
		sigScroll.getViewport().setBackground(Color.BLACK);
		outputPanel.add(sigScroll, BorderLayout.CENTER);

		infoArea = new JTextArea();
		infoArea.setRows(4);
		infoArea.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createEmptyBorder(10, 10, 10, 10),
				BorderFactory.createRaisedBevelBorder()));
		infoArea.setBackground(null);
		infoArea.setFont(Toolkit.MONO_FONT);
		infoArea.setEditable(false);
		outputPanel.add(infoArea, BorderLayout.SOUTH);

		splitPane.setRightComponent(outputPanel);

		// Bottom action panel
		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton clearButton = new JButton("Clear", Toolkit.menuIcon(0xe835));
		clearButton.setToolTipText("Clear all fields");
		clearButton.addActionListener(e -> {
			dataArea.setText("");
			signatureArea.setText("");
			infoArea.setText("");
		});
		actionPanel.add(clearButton);

		updateInfo(null);
	}

	private void updateTooltip() {
		String fmt = (String) formatCombo.getSelectedItem();
		if (FORMAT_CVX.equals(fmt)) {
			dataArea.setToolTipText("Convex Lisp value — signed as ref encoding (compatible with SignedData)");
		} else if (FORMAT_HEX.equals(fmt)) {
			dataArea.setToolTipText("Raw bytes as hex");
		} else {
			dataArea.setToolTipText("UTF-8 text — signed as raw byte encoding");
		}
	}

	/**
	 * Result of parsing the data area: the bytes to sign, plus optional
	 * warning text for CVX Data with external refs.
	 */
	private static class ParseResult {
		final Blob message;
		final String warning;

		ParseResult(Blob message, String warning) {
			this.message = message;
			this.warning = warning;
		}
	}

	/**
	 * Parses the data area according to the selected format.
	 * Returns the message bytes to sign/verify, or null on error.
	 */
	private ParseResult parseData() {
		String text = dataArea.getText().trim();
		if (text.isEmpty()) {
			updateInfo("No data entered");
			return null;
		}

		String fmt = (String) formatCombo.getSelectedItem();

		if (FORMAT_HEX.equals(fmt)) {
			try {
				byte[] bs = Utils.hexToBytes(Utils.stripWhiteSpace(text));
				return new ParseResult(Blob.wrap(bs), null);
			} catch (Exception ex) {
				updateInfo("Invalid hex: " + ex.getMessage());
				return null;
			}
		} else if (FORMAT_CVX.equals(fmt)) {
			try {
				ACell value = Reader.read(text);
				Ref<ACell> ref = Ref.get(value);
				Blob encoding = ref.getEncoding();

				String warning = null;
				if (Cells.branchCount(value) > 0) {
					warning = "WARNING: Value contains external refs — the ref encoding "
							+ "is a hash reference, not the complete value";
				}
				return new ParseResult(encoding, warning);
			} catch (Exception ex) {
				updateInfo("Failed to parse CVX data: " + ex.getMessage());
				return null;
			}
		} else {
			byte[] bs = text.getBytes(StandardCharsets.UTF_8);
			return new ParseResult(Blob.wrap(bs), null);
		}
	}

	/**
	 * Gets the unlocked key pair from the combo, or null on failure.
	 */
	private AKeyPair getKeyPair() {
		AWalletEntry walletEntry = keyCombo.getWalletEntry();
		if (walletEntry == null) {
			updateInfo("No key selected");
			return null;
		}
		if (walletEntry.isLocked()) {
			boolean unlocked = UnlockWalletDialog.offerUnlock(this, walletEntry);
			if (!unlocked) {
				updateInfo("Key is locked — unlock cancelled");
				return null;
			}
		}
		AKeyPair kp = walletEntry.getKeyPair();
		if (kp == null) {
			updateInfo("Could not get key pair from wallet entry");
			return null;
		}
		return kp;
	}

	private class SignAction implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent e) {
			try {
				ParseResult parsed = parseData();
				if (parsed == null) return;

				AKeyPair kp = getKeyPair();
				if (kp == null) return;

				ASignature sig = kp.sign(parsed.message);
				signatureArea.setText(sig.toHexString());

				StringBuilder sb = new StringBuilder();
				sb.append("Signed By:  ").append(kp.getAccountKey().toChecksumHex()).append("\n");
				sb.append("Data Size:  ").append(parsed.message.count()).append(" bytes\n");
				sb.append("Signature:  64 bytes (Ed25519)");
				if (parsed.warning != null) {
					sb.append("\n").append(parsed.warning);
				}
				updateInfo(sb.toString());
			} catch (Exception ex) {
				signatureArea.setText("");
				updateInfo("Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			}
		}
	}

	private class VerifyAction implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent e) {
			try {
				ParseResult parsed = parseData();
				if (parsed == null) return;

				String sigHex = Utils.stripWhiteSpace(signatureArea.getText().trim());
				if (sigHex.isEmpty()) {
					updateInfo("No signature to verify — paste or sign first");
					return;
				}
				ASignature sig = ASignature.fromHex(sigHex);

				AWalletEntry walletEntry = keyCombo.getWalletEntry();
				if (walletEntry == null) {
					updateInfo("No key selected");
					return;
				}
				AccountKey publicKey = walletEntry.getPublicKey();

				boolean valid = sig.verify(parsed.message, publicKey);
				StringBuilder sb = new StringBuilder();
				sb.append("Public Key: ").append(publicKey.toChecksumHex()).append("\n");
				sb.append("Data Size:  ").append(parsed.message.count()).append(" bytes\n");
				sb.append("Result:     ").append(valid ? "VALID" : "INVALID");
				if (parsed.warning != null) {
					sb.append("\n").append(parsed.warning);
				}
				updateInfo(sb.toString());
			} catch (Exception ex) {
				updateInfo("Verification error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			}
		}
	}

	private void updateInfo(String text) {
		infoArea.setText(text == null ? "" : text);
	}
}
