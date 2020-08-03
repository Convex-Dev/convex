package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;

import convex.core.crypto.WalletEntry;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.lang.Reader;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerManager;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;

@SuppressWarnings("serial")
public class REPLPanel extends JPanel {

	JTextArea inputArea;
	JTextArea outputArea;
	private JButton btnClear;
	private JButton btnInfo;

	private JPanel panel_1;

	private AccountChooserPanel execPanel;

	private final Connection peerConnection;

	private static final Logger log = Logger.getLogger(REPLPanel.class.getName());

	public void setInput(String s) {
		inputArea.setText(s);
	}

	@Override
	public void setVisible(boolean value) {
		super.setVisible(value);
		if (value) inputArea.requestFocusInWindow();
	}

	public final Consumer<Message> resultHandler = new ResultConsumer() {
		@Override
		protected void handleResult(Object m) {
			outputArea.append(" => " + m + "\n");
			outputArea.setCaretPosition(outputArea.getDocument().getLength());
		}
		
		@Override
		protected void handleError(long id, Object code, Object msg) {
			outputArea.append(" Exception: " + code + " "+ msg);
		}
	};

	/**
	 * Create the panel.
	 */
	public REPLPanel(PeerView peerView) {
		setLayout(new BorderLayout(0, 0));

		InetSocketAddress addr = peerView.getHostAddress();
		if (addr == null) {
			JOptionPane.showMessageDialog(this, "Error: peer shut down already?");
			throw new Error("Connect fail, no remote address");
		}
		try {
			// Connect to peer as a client
			peerConnection = Connection.connect(addr, resultHandler, Stores.CLIENT_STORE);
		} catch (IOException ex) {
			throw Utils.sneakyThrow(ex);
		}

		JSplitPane splitPane = new JSplitPane();
		splitPane.setResizeWeight(0.8);
		splitPane.setOneTouchExpandable(true);
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		outputArea = new JTextArea();
		outputArea.setRows(15);
		outputArea.setEditable(false);
		outputArea.setLineWrap(true);
		outputArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
		DefaultCaret caret = (DefaultCaret)outputArea.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		splitPane.setLeftComponent(new JScrollPane(outputArea));

		inputArea = new JTextArea();
		inputArea.setRows(5);
		inputArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
		inputArea.getDocument().addDocumentListener(new InputListener());
		splitPane.setRightComponent(new JScrollPane(inputArea));

		execPanel = new AccountChooserPanel();
		add(execPanel, BorderLayout.NORTH);

		panel_1 = new ActionPanel();
		add(panel_1, BorderLayout.SOUTH);

		btnClear = new JButton("Clear");
		panel_1.add(btnClear);
		btnClear.addActionListener(e -> outputArea.setText(""));

		btnInfo = new JButton("Connection Info");
		panel_1.add(btnInfo);
		btnInfo.addActionListener(e -> {
			Connection pc = peerConnection;
			String infoString = "";
			infoString += "Remote address:  " + pc.getRemoteAddress() + "\n";
			infoString += "Local address:  " + pc.getLocalAddress() + "\n";
			infoString += "Recieved count:  " + pc.getReceivedCount() + "\n";
			pc.wakeUp();

			JOptionPane.showMessageDialog(this, infoString);
		});

		// Get initial focus in REPL input area
		addComponentListener(new ComponentAdapter() {
			public void componentShown(ComponentEvent ce) {
				inputArea.requestFocusInWindow();
			}
		});
	}

	private void sendMessage(String s) {
		if (s.isBlank()) return;
		SwingUtilities.invokeLater(() -> {
			outputArea.append(s);
			outputArea.append("\n");
			try {
				Object message = Reader.read(s);
				long id;
				String mode = execPanel.getMode();
				if (mode.equals("Query")) {
					WalletEntry we = execPanel.getWalletEntry();
					if (we == null) {
						id = peerConnection.sendQuery(message);
					} else {
						id = peerConnection.sendQuery(message, we.getAddress());
					}
				} else if (mode.equals("Transact")) {
					WalletEntry we = execPanel.getWalletEntry();
					if ((we == null) || (we.isLocked())) {
						JOptionPane.showMessageDialog(this,
								"Please select an address to use for transactions before sending");
						return;
					}
					Address address = we.getAddress();
					AccountStatus as = PeerManager.getLatestState().getAccount(address);
					if (as == null) {
						JOptionPane.showMessageDialog(this, "Cannot send transaction: account does not exist");
						return;
					}
					long nonce = as.getSequence() + 1;
					ATransaction trans = Invoke.create(nonce, message);
					id = peerConnection.sendTransaction(we.sign(trans));
				} else {
					throw new Error("Unrecognosed REPL mode: " + mode);
				}
				log.finer("Sent message with ID: " + id);
			} catch (Throwable t) {
				outputArea.append(" SEND ERROR: ");
				outputArea.append(t.getMessage() + "\n");
			}
			inputArea.setText("");
		});
	}

	/**
	 * Listener to detect returns at the end of the input box => send message
	 */
	private class InputListener implements DocumentListener {

		@Override
		public void insertUpdate(DocumentEvent e) {
			int len = e.getLength();
			int off = e.getOffset();
			String s = inputArea.getText();
			if ((len == 1) && (len + off == s.length()) && (s.charAt(off) == '\n')) {
				sendMessage(s.trim());
			}
		}

		@Override
		public void removeUpdate(DocumentEvent e) {
			// nothing special
		}

		@Override
		public void changedUpdate(DocumentEvent e) {
			// nothing special
		}
	}

}
