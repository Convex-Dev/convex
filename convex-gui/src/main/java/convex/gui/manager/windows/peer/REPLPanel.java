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
import javax.swing.JCheckBox;
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
import convex.core.data.List;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ActionPanel;
import convex.gui.components.RightCopyMenu;
import convex.gui.components.Toast;
import convex.gui.utils.CVXHighlighter;

@SuppressWarnings("serial")
public class REPLPanel extends JPanel {

	JTextPane inputArea;
	JTextPane outputArea;
	private JButton btnClear;
	private JButton btnInfo;
	private JCheckBox btnResults;
	private JCheckBox btnTiming;
	private JCheckBox btnCompile;
	
	private ArrayList<String> history=new ArrayList<>();
	private int historyPosition=0;

	private InputListener inputListener=new InputListener();

	private Font OUTPUT_FONT=new Font("Monospaced", Font.PLAIN, 24);
	private Font INPUT_FONT=new Font("Monospaced", Font.PLAIN, 30);
	private Color DEFAULT_OUTPUT_COLOR=Color.LIGHT_GRAY;
	
	private JPanel panel_1;

	private AccountChooserPanel execPanel;

	private final Convex convex;

	private static final Logger log = LoggerFactory.getLogger(REPLPanel.class.getName());

	public void setInput(String s) {
		inputArea.setText(s);
		updateHighlight();
	}

	@Override
	public void setVisible(boolean value) {
		super.setVisible(value);
		if (value) inputArea.requestFocusInWindow();
	}

	protected void handleResult(long start,Result r) {
		if (btnTiming.isSelected()) {
			addOutput(outputArea,"Completion time: " + (Utils.getCurrentTimestamp()-start) + " ms\n");
		}
		if (btnResults.isSelected()) {
			handleResult((ACell)r);
	    } else if (r.isError()) {
			handleError(r.getErrorCode(),r.getValue(),r.getTrace());
		} else {
			handleResult((ACell)r.getValue());
		}
		execPanel.updateBalance();
	}

	protected void handleResult(ACell m) {
		AString s=RT.print(m);
		String resultString=(s==null)?"Print limit exceeded!":s.toString();
		
		int start=outputArea.getDocument().getLength();
		addOutput(outputArea," => " + resultString + "\n");
		int end=outputArea.getDocument().getLength();
		updateHighlight(outputArea,start,end-start);
		
		outputArea.setCaretPosition(end);
	}
	
	protected void handleError(ACell code, ACell msg, AVector<AString> trace) {
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
		execPanel=new AccountChooserPanel(null,convex);
		
		setLayout(new BorderLayout(0, 0));
		
		// Set up account chooser panel
		add(execPanel, BorderLayout.NORTH);

		// Split pane for main GUI elements

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
		outputArea.setBackground(new Color(10,10,10));
		outputArea.setToolTipText("This area shows a log of output from transaction execution");
		//DefaultCaret caret = (DefaultCaret)(outputArea.getCaret());
		//caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		splitPane.setLeftComponent(new JScrollPane(outputArea));

		inputArea = new JTextPane();
		inputArea.setFont(INPUT_FONT);
		inputArea.getDocument().addDocumentListener(inputListener);
		inputArea.addKeyListener(inputListener);
		inputArea.setBackground(Color.BLACK);
		inputArea.setToolTipText("Input commands here. Press Enter at the end of input to send.");
		RightCopyMenu.addTo(inputArea);
		//inputArea.setForeground(Color.GREEN);

		splitPane.setRightComponent(new JScrollPane(inputArea));
		
		// stop ctrl+arrow losing focus
		setFocusTraversalKeysEnabled(false);
		inputArea.setFocusTraversalKeysEnabled(false);
		

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
			try {
				sb.append("Sequence:    " + convex.getSequence() + "\n");
			} catch (Exception e1) {
				log.info("Failed to get sequence number");
			}
			sb.append("Account:     " + RT.toString(convex.getAddress()) + "\n");
			sb.append("Public Key:  " + RT.toString(convex.getAccountKey()) + "\n");
			sb.append("Connected?:  " + convex.isConnected()+"\n");

			String infoString = sb.toString();
			JOptionPane.showMessageDialog(this, infoString);
		});
		
		btnResults=new JCheckBox("Full Results");
		btnResults.setToolTipText("Tick to show full Result record returned from peer.");
		panel_1.add(btnResults);
		
		btnTiming=new JCheckBox("Show Timing");
		btnTiming.setToolTipText("Tick to receive execution time report after each transaction.");
		panel_1.add(btnTiming);
		
		btnCompile=new JCheckBox("Precompile");
		btnCompile.setToolTipText("Tick to compile code before sending transaction. Usually reduces juice costs.");
		btnCompile.setSelected(convex.getLocalServer()!=null); // default: only do this if local
		panel_1.add(btnCompile);

		
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
		if (we.isLocked()) return null;
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
			inputArea.setText("");
			addOutput(outputArea,s);
			addOutput(outputArea,"\n");
			try {
				AList<ACell> forms = Reader.readAll(s);
				ACell code = (forms.count()==1)?forms.get(0):forms.cons(Symbols.DO);
				Future<Result> future;
				String mode = execPanel.getMode();
				
				if (btnCompile.isSelected()) {
					ACell compileStep=List.of(Symbols.COMPILE,List.of(Symbols.QUOTE,code));
					Result cr=convex.querySync(compileStep);
					code=cr.getValue();
				}

				long start=Utils.getCurrentTimestamp();

				if (mode.equals("Query")) {
					Address qaddr=getAddress();
					if (qaddr == null) {
						future = convex.query(code,null);
					} else {
						future = convex.query(code, qaddr);
					}
				} else if (mode.equals("Transact")) {
					WalletEntry we = execPanel.getWalletEntry();
					if ((we == null) || (we.isLocked())) {
						Toast.display(this, "Can't transact without an unlocked key pair", Color.RED);
						return;
					}
					Address address = getAddress();
					ATransaction trans = Invoke.create(address,0, code);
					convex.setAddress(address);
					convex.setKeyPair(getKeyPair());
					future = convex.transact(trans);
				} else {
					throw new Error("Unrecognosed REPL mode: " + mode);
				}
				log.trace("Sent message");
				
				handleResult(start,future.get(5000, TimeUnit.MILLISECONDS));
			} catch (TimeoutException t) {
				addOutput(outputArea," TIMEOUT waiting for result");
			} catch (Throwable t) {
				addOutput(outputArea," SEND ERROR: ");
				addOutput(outputArea,t.getMessage() + "\n");
				t.printStackTrace();
			}
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
			int code = e.getKeyCode();
			// CTRL or Shift plus arrow scrolls through history
			if (e.isControlDown()||e.isShiftDown()) {
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
			
			// Enter sends unless a meta key held down
			if (code==KeyEvent.VK_ENTER) {
				try {
					Document doc=inputArea.getDocument();
					int docLen=doc.getLength();
					if (e.isControlDown()||e.isShiftDown()) {
						doc.insertString(docLen, "\n",SimpleAttributeSet.EMPTY);
					} else {
						int off = inputArea.getCaretPosition();
							
						// Detect Enter at end of form
						if ((off==docLen)) {
							String s=doc.getText(0, docLen);
							sendMessage(s.trim());
						}
					}
				} catch (BadLocationException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}			
			}
		}

		@Override
		public void keyReleased(KeyEvent e) {
			
		}
	}

}
