package convex.gui.manager.windows.peer;
		
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerView;

@SuppressWarnings("serial")
public class REPLPanel extends JPanel {

	JTextArea inputArea;
	JTextArea outputArea;
	private JButton btnClear;
	private JButton btnInfo;
	
	private ArrayList<String> history=new ArrayList<>();
	private int historyPosition=0;

	private InputListener inputListener=new InputListener();

	
	private JPanel panel_1;

	private AccountChooserPanel execPanel = new AccountChooserPanel();

	private final Convex convex;

	private static final Logger log = LoggerFactory.getLogger(REPLPanel.class.getName());

	public void setInput(String s) {
		inputArea.setText(s);
	}

	@Override
	public void setVisible(boolean value) {
		super.setVisible(value);
		if (value) inputArea.requestFocusInWindow();
	}

	protected void handleResult(Result r) {
		if (r.isError()) {
			handleError(r.getErrorCode(),r.getValue(),r.getTrace());
		} else {
			handleResult((Object)r.getValue());
		}
	}

	protected void handleResult(Object m) {
		outputArea.append(" => " + m + "\n");
		outputArea.setCaretPosition(outputArea.getDocument().getLength());
	}
	
	protected void handleError(Object code, Object msg, AVector<AString> trace) {
		outputArea.append(" Exception: " + code + " "+ msg+"\n");
		if (trace!=null) for (AString s: trace) {
			outputArea.append(" - "+s.toString()+"\n");
		}
	}

	/**
	 * Create the panel.
	 * @param peerView 
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
			convex = Convex.connect(addr, getAddress(),getKeyPair());
		} catch (Exception ex) {
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
		//DefaultCaret caret = (DefaultCaret)(outputArea.getCaret());
		//caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		splitPane.setLeftComponent(new JScrollPane(outputArea));

		inputArea = new JTextArea();
		inputArea.setRows(5);
		inputArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
		inputArea.getDocument().addDocumentListener(inputListener);
		inputArea.addKeyListener(inputListener);
		splitPane.setRightComponent(new JScrollPane(inputArea));
		
		// stop ctrl+arrow losing focus
		setFocusTraversalKeysEnabled(false);
		inputArea.setFocusTraversalKeysEnabled(false);
		
		
		add(execPanel, BorderLayout.NORTH);

		panel_1 = new ActionPanel();
		add(panel_1, BorderLayout.SOUTH);

		btnClear = new JButton("Clear");
		panel_1.add(btnClear);
		btnClear.addActionListener(e -> outputArea.setText(""));

		btnInfo = new JButton("Connection Info");
		panel_1.add(btnInfo);
		btnInfo.addActionListener(e -> {
			String infoString = "";
			infoString += "Remote host:  " + convex.getRemoteAddress() + "\n";
			infoString += "Sequence:  " + convex.getSequence() + "\n";
			infoString += "Connection Account:  " + convex.getAddress() + "\n";

			JOptionPane.showMessageDialog(this, infoString);
		});

		// Get initial focus in REPL input area
		addComponentListener(new ComponentAdapter() {
			public void componentShown(ComponentEvent ce) {
				inputArea.requestFocusInWindow();
			}
		});
	}

	private AKeyPair getKeyPair() {
		WalletEntry we=execPanel.getWalletEntry();
		if (we==null) return null;
		return we.getKeyPair();
	}
	
	private Address getAddress() {
		WalletEntry we=execPanel.getWalletEntry();
		return we.getAddress();
	}

	private void sendMessage(String s) {
		if (s.isBlank()) return;
		
		history.add(s);
		historyPosition=history.size();
		
		SwingUtilities.invokeLater(() -> {
			outputArea.append(s);
			outputArea.append("\n");
			try {
				AList<ACell> forms = Reader.readAll(s);
				ACell message = (forms.count()==1)?forms.get(0):forms.cons(Symbols.DO);
				Future<Result> future;
				String mode = execPanel.getMode();
				if (mode.equals("Query")) {
					AKeyPair kp=getKeyPair();
					if (kp == null) {
						future = convex.query(message,null);
					} else {
						future = convex.query(message, getAddress());
					}
				} else if (mode.equals("Transact")) {
					WalletEntry we = execPanel.getWalletEntry();
					if ((we == null) || (we.isLocked())) {
						JOptionPane.showMessageDialog(this,
								"Please select an address to use for transactions before sending");
						return;
					}
					Address address = getAddress();
					ATransaction trans = Invoke.create(address,-1, message);
					future = convex.transact(trans);
				} else {
					throw new Error("Unrecognosed REPL mode: " + mode);
				}
				log.trace("Sent message");
				
				handleResult(future.get(5000, TimeUnit.MILLISECONDS));
			} catch (TimeoutException t) {
				outputArea.append(" TIMEOUT waiting for result");
			} catch (Throwable t) {
				outputArea.append(" SEND ERROR: ");
				outputArea.append(t.getMessage() + "\n");
				t.printStackTrace();
			}
			inputArea.setText("");
		});
	}

	/**
	 * Listener to detect returns at the end of the input box => send message
	 */
	private class InputListener implements DocumentListener, KeyListener {

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

		@Override
		public void keyTyped(KeyEvent e) {

		}

		@Override
		public void keyPressed(KeyEvent e) {
			// System.out.println(e);
			if (e.isControlDown()) {
				int code = e.getKeyCode();
				int hSize=history.size();
				if (code==KeyEvent.VK_UP) {

					if (historyPosition>0) {
						if (historyPosition==hSize) {
							// store current in history
							String s=inputArea.getText();
							history.add(s);
						}
						historyPosition--;
						setInput(history.get(historyPosition));
					}
					e.consume(); // mark event consumed
				} else if (code==KeyEvent.VK_DOWN) {
					if (historyPosition<hSize-1) {
						historyPosition++;
						setInput(history.get(historyPosition));
					}
					e.consume(); // mark event consumed
				}
			}
		}

		@Override
		public void keyReleased(KeyEvent e) {
			
		}
	}

}
