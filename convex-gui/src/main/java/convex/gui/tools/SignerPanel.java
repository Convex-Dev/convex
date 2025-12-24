package convex.gui.tools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import convex.core.Coin;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.SignedData;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.lang.Reader;
import convex.core.lang.RT;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.CodeLabel;
import convex.gui.components.CodePane;
import convex.gui.components.account.AddressCombo;
import convex.gui.components.account.KeyPairCombo;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SignerPanel extends JPanel {

	private AddressCombo addressField;
	private JSpinner sequenceSpinner;
	private KeyPairCombo keyCombo;
	private CodeLabel encodedArea;
	private ActionButton signButton;
	private JTextArea infoLabel;
	private JTextArea previewArea;
	
	// Invoke tab fields
	private CodePane invokeCodeArea;
	
	// Transfer tab fields
	private AddressCombo transferTargetField;
	private JTextField transferAmountField;
	
	// Call tab fields
	private AddressCombo callTargetField;
	private JSpinner callOfferSpinner;
	private JTextField callFunctionField;
	private CodePane callArgsArea;
	
	private JTabbedPane transactionTypeTabs;

	public SignerPanel() {
		setLayout(new BorderLayout(0, 0));
		
		// Instructions panel
		JPanel instructionsPanel = new JPanel();
		add(instructionsPanel, BorderLayout.NORTH);
		JLabel lblNewLabel = new JLabel("Construct and sign a Convex transaction");
		instructionsPanel.add(lblNewLabel);

		// Main split pane
		JSplitPane splitPane = new JSplitPane();
		splitPane.setOneTouchExpandable(true);
		splitPane.setResizeWeight(0.5);
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		// Top panel - Input fields
		JPanel inputPanel = new JPanel();
		inputPanel.setLayout(new MigLayout("fillx, insets 10", "[][grow][]", "[]10[]10[]10[]10[]10[]10[]"));
		
		// Common fields: Address and Sequence (outside tabs)
		JLabel addressLabel = new JLabel("Address:");
		inputPanel.add(addressLabel);
		addressField = new AddressCombo();
		inputPanel.add(addressField, "growx");
		
		JLabel sequenceLabel = new JLabel("Sequence:");
		inputPanel.add(sequenceLabel);
		sequenceSpinner = new JSpinner(new SpinnerNumberModel(1L, 1L, Long.MAX_VALUE, 1L));
		inputPanel.add(sequenceSpinner);
		
		// Key selector
		JLabel keyLabel = new JLabel("Key:");
		inputPanel.add(keyLabel);
		keyCombo = KeyPairCombo.create();
		inputPanel.add(keyCombo, "growx, wrap, span");
		
		// Transaction type tabs
		transactionTypeTabs = new JTabbedPane();
		transactionTypeTabs.addTab("Invoke", createInvokePanel());
		transactionTypeTabs.addTab("Transfer", createTransferPanel());
		transactionTypeTabs.addTab("Call", createCallPanel());
		inputPanel.add(transactionTypeTabs, "grow, wrap, span");
		
		
		// Preview section
		previewArea = new JTextArea("Enter transaction details to preview");
		previewArea.setEditable(false);
		previewArea.setFont(Toolkit.MONO_FONT);
		previewArea.setRows(4);
		inputPanel.add(previewArea, "span 3, grow, wrap");
		
		// Sign button
		signButton = new ActionButton("Sign", 0xe5ca, new SignActionListener());
		signButton.setToolTipText("Create, sign, and encode the transaction");
		signButton.setEnabled(false);
		inputPanel.add(signButton, "span 2, center");
		
		splitPane.setLeftComponent(inputPanel);

		// Bottom panel - Output
		JPanel outputPanel = new JPanel();
		outputPanel.setLayout(new BorderLayout(0, 0));

		encodedArea = new CodeLabel();
		encodedArea.setEditable(false);
		encodedArea.setToolTipText("Encoded signed transaction (hex)");
		encodedArea.setFont(Toolkit.MONO_FONT);
		encodedArea.setBackground(Color.BLACK);
		encodedArea.setLineWrap(true);
		encodedArea.setMaxColumns(65);
		JScrollPane encodedScroll = new JScrollPane(encodedArea);
		encodedScroll.getViewport().setBackground(Color.BLACK);
		outputPanel.add(encodedScroll, BorderLayout.CENTER);

		infoLabel = new JTextArea();
		infoLabel.setRows(3);
		infoLabel.setToolTipText("Transaction information");
		infoLabel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createEmptyBorder(10, 10, 10, 10),
				BorderFactory.createRaisedBevelBorder()));
		infoLabel.setBackground(null);
		infoLabel.setFont(Toolkit.MONO_FONT);
		infoLabel.setEditable(false);
		outputPanel.add(infoLabel, BorderLayout.SOUTH);

		splitPane.setRightComponent(outputPanel);

		// Action panel at bottom
		JPanel buttonPanel = new ActionPanel();
		add(buttonPanel, BorderLayout.SOUTH);

		JButton clearButton = new JButton("Clear", Toolkit.menuIcon(0xe835));
		clearButton.setToolTipText("Clear all input and output fields");
		clearButton.addActionListener(e -> {
			sequenceSpinner.setValue(1L);
			invokeCodeArea.setText("");
			transferAmountField.setText("0");
			callOfferSpinner.setValue(0L);
			callFunctionField.setText("");
			callArgsArea.setText("");
			encodedArea.setText("");
			infoLabel.setText("");
			updatePreview();
		});
		buttonPanel.add(clearButton);

		updateInfoLabel(null, null);
		
		// Listeners to keep preview up to date
		addressField.addItemListener(e -> updatePreview());
		sequenceSpinner.addChangeListener(e -> updatePreview());
		transactionTypeTabs.addChangeListener(e -> updatePreview());
		

		updatePreview();
	}
	
	private JPanel createInvokePanel() {
		JPanel panel = new JPanel();
		panel.setLayout(new MigLayout("fillx, insets 5", "[grow]", "[]"));
		
		JLabel codeLabel = new JLabel("Code:");
		panel.add(codeLabel, "wrap");
		invokeCodeArea = new CodePane();
		invokeCodeArea.setToolTipText("Enter Convex Lisp code here");
		invokeCodeArea.setEditable(true);
		invokeCodeArea.setFont(Toolkit.MONO_FONT);
		invokeCodeArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updatePreview()));
		JScrollPane codeScroll = new JScrollPane(invokeCodeArea);
		codeScroll.setPreferredSize(new java.awt.Dimension(400, 150));
		panel.add(codeScroll, "grow");
		
		return panel;
	}
	
	private JPanel createTransferPanel() {
		JPanel panel = new JPanel();
		panel.setLayout(new MigLayout("fillx, insets 5", "[][grow]", "[]10[]"));
		
		JLabel targetLabel = new JLabel("Target Address:");
		panel.add(targetLabel);
		transferTargetField = new AddressCombo();
		panel.add(transferTargetField, "growx, wrap");
		transferTargetField.addItemListener(e -> updatePreview());
		
		JLabel amountLabel = new JLabel("Amount:");
		panel.add(amountLabel);
		transferAmountField = new JTextField();
		transferAmountField.setToolTipText("Amount in Convex Coins (long value)");
		transferAmountField.setFont(Toolkit.MONO_FONT);
		panel.add(transferAmountField, "growx");
		transferAmountField.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updatePreview()));
		
		return panel;
	}
	
	private JPanel createCallPanel() {
		JPanel panel = new JPanel();
		panel.setLayout(new MigLayout("fillx, insets 5", "[][grow]", "[]10[]10[]10[]"));
		
		JLabel targetLabel = new JLabel("Target Address:");
		panel.add(targetLabel);
		callTargetField = new AddressCombo();
		panel.add(callTargetField, "growx, wrap");
		callTargetField.addItemListener(e -> updatePreview());
		
		JLabel offerLabel = new JLabel("Offer:");
		panel.add(offerLabel);
		callOfferSpinner = new JSpinner(new SpinnerNumberModel(0L, 0L, Long.MAX_VALUE, 1L));
		callOfferSpinner.setToolTipText("Amount to offer (default: 0)");
		panel.add(callOfferSpinner, "growx, wrap");
		callOfferSpinner.addChangeListener(e -> updatePreview());
		
		JLabel functionLabel = new JLabel("Function Name:");
		panel.add(functionLabel);
		callFunctionField = new JTextField();
		callFunctionField.setToolTipText("Function name as a symbol (e.g., transfer)");
		callFunctionField.setFont(Toolkit.MONO_FONT);
		panel.add(callFunctionField, "growx, wrap");
		callFunctionField.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updatePreview()));
		
		JLabel argsLabel = new JLabel("Arguments:");
		panel.add(argsLabel, "top");
		callArgsArea = new CodePane();
		callArgsArea.setToolTipText("Arguments as a vector (e.g., [arg1 arg2])");
		callArgsArea.setEditable(true);
		callArgsArea.setFont(Toolkit.MONO_FONT);
		JScrollPane argsScroll = new JScrollPane(callArgsArea);
		argsScroll.setPreferredSize(new java.awt.Dimension(400, 100));
		panel.add(argsScroll, "grow");
		callArgsArea.getDocument().addDocumentListener(Toolkit.createDocumentListener(() -> updatePreview()));
		
		return panel;
	}

	private class SignActionListener implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent e) {
			try {
				// Build transaction (should be valid if Sign button is enabled)
				StringBuilder error = new StringBuilder();
				ATransaction transaction = buildTransaction(error);
				if (transaction == null) {
					encodedArea.setText("Cannot sign: " + error.toString());
					updateInfoLabel(null, null);
					return;
				}

				// Get selected key
				AWalletEntry walletEntry = keyCombo.getWalletEntry();
				if (walletEntry == null) {
					encodedArea.setText("Error: No key selected");
					updateInfoLabel(null, null);
					return;
				}

				// Unlock key if needed
				if (walletEntry.isLocked()) {
					boolean unlocked = UnlockWalletDialog.offerUnlock(SignerPanel.this, walletEntry);
					if (!unlocked) {
						encodedArea.setText("Error: Key is locked and unlock was cancelled");
						updateInfoLabel(null, null);
						return;
					}
				}

				// Get key pair
				AKeyPair keyPair = walletEntry.getKeyPair();
				if (keyPair == null) {
					encodedArea.setText("Error: Could not get key pair from wallet entry");
					updateInfoLabel(null, null);
					return;
				}

				// Sign transaction
				SignedData<ATransaction> signedTransaction = keyPair.signData(transaction);

				// Encode transaction
				Blob encoded = Format.encodeMultiCell(signedTransaction, true);
				String hex = encoded.toHexString();

				// Display encoded transaction
				encodedArea.setText(hex);

				// Update info label
				updateInfoLabel(transaction, signedTransaction);

			} catch (Exception ex) {
				encodedArea.setText("Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
				updateInfoLabel(null, null);
				ex.printStackTrace();
			}
		}
	}
	
	/**
	 * Attempts to build a transaction from the current UI state.
	 * On success, returns the transaction and leaves errorOut empty.
	 * On failure, returns null and writes a human-readable error into errorOut.
	 */
	private ATransaction buildTransaction(StringBuilder errorOut) {
		// Get address
		Address address = addressField.getAddress();
		if (address == null) {
			errorOut.append("Invalid origin address");
			return null;
		}

		// Get sequence
		long sequence = ((Number) sequenceSpinner.getValue()).longValue();

		// Create transaction based on selected tab
		int selectedTab = transactionTypeTabs.getSelectedIndex();
		
		if (selectedTab == 0) {
			// Invoke
			String codeText = invokeCodeArea.getText();
			if (codeText == null || codeText.trim().isEmpty()) {
				errorOut.append("Code cannot be empty");
				return null;
			}
			ACell code;
			try {
				code = Reader.read(codeText);
			} catch (Exception ex) {
				errorOut.append("Failed to parse code: ").append(ex.getMessage());
				return null;
			}
			return Invoke.create(address, sequence, code);
			
		} else if (selectedTab == 1) {
			// Transfer
			Address target = transferTargetField.getAddress();
			if (target == null) {
				errorOut.append("Invalid target address");
				return null;
			}
			String amountText = transferAmountField.getText().trim();
			if (amountText.isEmpty()) {
				errorOut.append("Amount cannot be empty");
				return null;
			}
			long amount;
			try {
				amount = Long.parseLong(amountText);
				if (!Coin.isValidAmount(amount)) {
					errorOut.append("Invalid amount");
					return null;
				}
			} catch (NumberFormatException ex) {
				errorOut.append("Amount must be a valid number: ").append(ex.getMessage());
				return null;
			}
			return Transfer.create(address, sequence, target, amount);
			
		} else if (selectedTab == 2) {
			// Call
			Address target = callTargetField.getAddress();
			if (target == null) {
				errorOut.append("Invalid target address");
				return null;
			}
			long offer = ((Number) callOfferSpinner.getValue()).longValue();
			if (!Coin.isValidAmount(offer)) {
				errorOut.append("Invalid offer amount");
				return null;
			}
			String functionText = callFunctionField.getText().trim();
			if (functionText.isEmpty()) {
				errorOut.append("Function name cannot be empty");
				return null;
			}
			Symbol functionName = Symbol.create(functionText);
			if (functionName == null) {
				errorOut.append("Invalid function name");
				return null;
			}
			String argsText = callArgsArea.getText().trim();
			AVector<ACell> args;
			if (argsText.isEmpty()) {
				args = Vectors.empty();
			} else {
				try {
					ACell argsCell = Reader.read(argsText);
					args = RT.ensureVector(argsCell);
					if (args == null) {
						errorOut.append("Arguments must be a vector");
						return null;
					}
				} catch (Exception ex) {
					errorOut.append("Failed to parse arguments: ").append(ex.getMessage());
					return null;
				}
			}
			return Call.create(address, sequence, target, offer, functionName, args);
		} else {
			errorOut.append("Unknown transaction type");
			return null;
		}
	}
	
	/**
	 * Updates the preview area and enables/disables the Sign button
	 * based on whether a valid transaction can be constructed.
	 */
	private void updatePreview() {
		StringBuilder error = new StringBuilder();
		ATransaction tx = buildTransaction(error);
		if (tx != null) {
			previewArea.setText(RT.print(tx).toString());
			signButton.setEnabled(true);
		} else {
			String msg = error.length() == 0 ? "No transaction" : error.toString();
			previewArea.setText(msg);
			signButton.setEnabled(false);
		}
	}

	private void updateInfoLabel(ATransaction transaction, SignedData<ATransaction> signedTransaction) {
		StringBuilder sb = new StringBuilder();
		if (transaction == null || signedTransaction == null) {
			sb.append("No transaction");
		} else {
			sb.append("TX Hash:     ").append(signedTransaction.getHash());
			sb.append("Signed By:   ").append(signedTransaction.getAccountKey().toChecksumHex()).append("\n");
			sb.append("Size:        ").append(Cells.storageSize(signedTransaction));
		}
		infoLabel.setText(sb.toString());
	}
}
