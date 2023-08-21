package convex.gui.manager.windows.peer;
		
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.WalletEntry;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ActionPanel;
import convex.gui.components.RightCopyMenu;
import convex.gui.utils.CVXHighlighter;

@SuppressWarnings("serial")
public class REPLPanel extends JPanel {

	JTextPane inputArea;
	JTextPane outputArea;
	private JButton btnClear;
	private JButton btnInfo;
	
	private ArrayList<String> history=new ArrayList<>();
	private int historyPosition=0;

	private InputListener inputListener=new InputListener();

	private Font OUTPUT_FONT=new Font("Monospaced", Font.PLAIN, 16);
	private Font INPUT_FONT=new Font("Monospaced", Font.PLAIN, 20);
	private Color DEFAULT_OUTPUT_COLOR=Color.LIGHT_GRAY;
	
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
			handleResult((ACell)r.getValue());
		}
	}

	protected void handleResult(ACell m) {
		String resultString=RT.print(m).toString();
		int start=outputArea.getDocument().getLength();
		addOutput(outputArea," => " + resultString + "\n");
		int end=outputArea.getDocument().getLength();
		updateHighlight(outputArea,start,end-start);
		
		outputArea.setCaretPosition(end);
	}
	
	protected void handleError(Object code, Object msg, AVector<AString> trace) {
		addOutput(outputArea," Exception: " + code + " "+ msg+"\n",Color.ORANGE);
		if (trace!=null) for (AString s: trace) {
			addOutput(outputArea," - "+s.toString()+"\n",Color.PINK);
		}
	}
	
	private void addOutput(JTextPane pane, String text) {
		addOutput(pane,text,DEFAULT_OUTPUT_COLOR);
		
	}
	
	private void addOutput(JTextPane pane, String text, Color c) {
		StyleContext sc = StyleContext.getDefaultStyleContext();
		AttributeSet aset = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, c);

		Document d=pane.getDocument();
		int len = d.getLength();
		try {
			d.insertString(len, text, aset);
		} catch (BadLocationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//pane.setCaretPosition(len);
		//pane.setCharacterAttributes(aset, false);
		//pane.replaceSelection(text);
		pane.repaint();
	}

	/**
	 * Create the panel.
	 * @param convex Convex connection instance
	 */
	public REPLPanel(Convex convex) {
		this.convex=convex;
		setLayout(new BorderLayout(0, 0));

		JSplitPane splitPane = new JSplitPane();
		splitPane.setResizeWeight(0.8);
		splitPane.setOneTouchExpandable(true);
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		outputArea = new JTextPane();
		//outputArea.setRows(15);
		outputArea.setEditable(false);
		//outputArea.setLineWrap(true);
		outputArea.setFont(OUTPUT_FONT);
		RightCopyMenu.addTo(outputArea);
		//outputArea.setForeground(Color.GREEN);
		//DefaultCaret caret = (DefaultCaret)(outputArea.getCaret());
		//caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		splitPane.setLeftComponent(new JScrollPane(outputArea));

		inputArea = new JTextPane();
		inputArea.setFont(INPUT_FONT);
		inputArea.getDocument().addDocumentListener(inputListener);
		inputArea.addKeyListener(inputListener);
		RightCopyMenu.addTo(inputArea);
		//inputArea.setForeground(Color.GREEN);

		splitPane.setRightComponent(new JScrollPane(inputArea));
		
		// stop ctrl+arrow losing focus
		setFocusTraversalKeysEnabled(false);
		inputArea.setFocusTraversalKeysEnabled(false);
		
		// Sett up account chooser panel
		add(execPanel, BorderLayout.NORTH);
		execPanel.selectAddress(convex.getAddress());

		panel_1 = new ActionPanel();
		add(panel_1, BorderLayout.SOUTH);

		btnClear = new JButton("Clear");
		panel_1.add(btnClear);
		btnClear.addActionListener(e -> outputArea.setText(""));

		btnInfo = new JButton("Connection Info");
		panel_1.add(btnInfo);
		btnInfo.addActionListener(e -> {
			StringBuilder sb=new StringBuilder();
			if (convex instanceof ConvexRemote) {
				sb.append("Remote host: " + convex.getHostAddress() + "\n");
			}
			sb.append("Sequence:    " + convex.getSequence() + "\n");
			sb.append("Account:     " + convex.getAddress() + "\n");
			sb.append("Public Key:  " + convex.getAccountKey() + "\n");
			sb.append("Connected?:  " + convex.isConnected()+"\n");

			String infoString = sb.toString();
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
			addOutput(outputArea,s);
			addOutput(outputArea,"\n");
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
					convex.setAddress(address);
					convex.setKeyPair(getKeyPair());
					future = convex.transact(trans);
				} else {
					throw new Error("Unrecognosed REPL mode: " + mode);
				}
				log.trace("Sent message");
				
				handleResult(future.get(5000, TimeUnit.MILLISECONDS));
			} catch (TimeoutException t) {
				addOutput(outputArea," TIMEOUT waiting for result");
			} catch (Throwable t) {
				addOutput(outputArea," SEND ERROR: ");
				addOutput(outputArea,t.getMessage() + "\n");
				t.printStackTrace();
			}
			inputArea.setText("");
		});
	}

	
	boolean highlighting=false;
	protected void updateHighlight() {
		int len=inputArea.getDocument().getLength();
		if (!highlighting) {
			highlighting=true;
			updateHighlight(inputArea,0,len);
		}
	}
	
	protected void updateHighlight(JTextPane pane,int start, int len) {
		SwingUtilities.invokeLater(()->{
			CVXHighlighter.highlight(pane,start,start+len);
			highlighting=false;
		});
	}
	
	/**
	 * Listener to detect returns at the end of the input box => send message
	 */
	private class InputListener implements DocumentListener, KeyListener {

		@Override
		public void insertUpdate(DocumentEvent e) {
			try {
				int off = e.getOffset();
				int len = e.getLength();
				int end=off+len;
				int docLen=e.getDocument().getLength();
				
				// Detect Enter at end of form
				if ((end==docLen) && ("\n".equals(e.getDocument().getText(end-1,1)))) {
					String s=e.getDocument().getText(0, docLen);
					sendMessage(s.trim());
				}
			} catch (BadLocationException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			updateHighlight();
		}

		@Override
		public void removeUpdate(DocumentEvent e) {
			updateHighlight();
		}

		@Override
		public void changedUpdate(DocumentEvent e) {
			updateHighlight();
		}

		@Override
		public void keyTyped(KeyEvent e) {

		}

		@Override
		public void keyPressed(KeyEvent e) {
			// CTRL or Shift scrolls through history
			if (e.isControlDown()||e.isShiftDown()) {
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
