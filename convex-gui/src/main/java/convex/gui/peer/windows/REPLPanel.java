package convex.gui.peer.windows;
		
import java.awt.Color;
import java.awt.Component;
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
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.List;
import convex.core.data.SignedData;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.AccountChooserPanel;
import convex.gui.components.ActionPanel;
import convex.gui.components.BaseTextPane;
import convex.gui.components.CodePane;
import convex.gui.utils.CVXHighlighter;
import net.miginfocom.swing.MigLayout;

/**
 * Panel presenting a general purpose REPL terminal
 */
@SuppressWarnings("serial")
public class REPLPanel extends JPanel {

	CodePane inputArea;
	CodePane outputArea;
	private JButton btnClear;
	private JButton btnInfo;
	private JCheckBox btnResults;
	private JCheckBox btnTiming;
	private JCheckBox btnCompile;
	private JCheckBox btnTX;
	
	private ArrayList<String> history=new ArrayList<>();
	private int historyPosition=0;

	private InputListener inputListener=new InputListener();

	private Font OUTPUT_FONT=new Font("Monospaced", Font.PLAIN, 24);
	private Font INPUT_FONT=new Font("Monospaced", Font.PLAIN, 30);
	private Color DEFAULT_OUTPUT_COLOR=Color.LIGHT_GRAY;
	
	private JPanel actionPanel;

	private AccountChooserPanel execPanel;

	private final Convex convex;
	private JSplitPane splitPane;

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
		outputArea.append(" Exception: " + code + " "+ msg+"\n",Color.ORANGE);
		if (trace!=null) for (AString s: trace) {
			outputArea.append(" - "+s.toString()+"\n",Color.PINK);
		}
	}
	
	private void addOutput(BaseTextPane pane, String text) {
		addOutput(pane,text,DEFAULT_OUTPUT_COLOR);	
	}
	
	private void addOutputWithHighlight(BaseTextPane pane, String text) {
		Document d=pane.getDocument();
		int start = d.getLength();
		pane.append(text,DEFAULT_OUTPUT_COLOR);	
		int end=d.getLength();
		if (end>start) updateHighlight(pane,start,end-start);
	}
	
	private void addOutput(JTextComponent pane, String text, Color c) {
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
		execPanel=new AccountChooserPanel(convex);
		
		setLayout(new MigLayout());
		
		// TOP Account Chooser
		// Set up account chooser panel
		add(execPanel, "dock north");

		// MAIN SPLIT PANE for main GUI elements

		splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		splitPane.setResizeWeight(0.7);
		splitPane.setOneTouchExpandable(true);
		add(splitPane, "dock center");

		outputArea = new CodePane();
		outputArea.setEditable(false);
		outputArea.setFont(OUTPUT_FONT);
		//outputArea.setForeground(Color.GREEN);
		outputArea.setBackground(new Color(10,10,10));
		outputArea.setToolTipText("Output from transaction execution");
		//DefaultCaret caret = (DefaultCaret)(outputArea.getCaret());
		//caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		splitPane.setLeftComponent(wrapScrollPane(outputArea));

		inputArea = new CodePane();
		inputArea.setFont(INPUT_FONT);
		inputArea.getDocument().addDocumentListener(inputListener);
		inputArea.addKeyListener(inputListener);
		inputArea.setToolTipText("Input commands here (Press Enter at the end of input to send)");
		//inputArea.setForeground(Color.GREEN);

		splitPane.setRightComponent(wrapScrollPane(inputArea));
		
		// stop ctrl+arrow losing focus
		setFocusTraversalKeysEnabled(false);
		
		// BOTTOM ACTION PANEL
		
		actionPanel = new ActionPanel();
		add(actionPanel, "dock south");

		btnClear = new JButton("Clear");
		actionPanel.add(btnClear);
		btnClear.addActionListener(e -> {
			outputArea.setText("");
		});

		btnInfo = new JButton("Connection Info");
		actionPanel.add(btnInfo);
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
		
		btnTX=new JCheckBox("Show transaction");
		btnTX.setToolTipText("Tick to show full transaction details.");
		actionPanel.add(btnTX);
		
		btnResults=new JCheckBox("Full Results");
		btnResults.setToolTipText("Tick to show full Result record returned from peer.");
		actionPanel.add(btnResults);
		
		btnTiming=new JCheckBox("Show Timing");
		btnTiming.setToolTipText("Tick to receive execution time report after each transaction.");
		actionPanel.add(btnTiming);
		
		btnCompile=new JCheckBox("Precompile");
		btnCompile.setToolTipText("Tick to compile code before sending transaction. Usually reduces juice costs.");
		btnCompile.setSelected(convex.getLocalServer()!=null); // default: only do this if local
		actionPanel.add(btnCompile);

		
		// Get initial focus in REPL input area
		addComponentListener(new ComponentAdapter() {
			public void componentShown(ComponentEvent ce) {
				inputArea.requestFocusInWindow();
			}
		});
	}

	private Component wrapScrollPane(CodePane codePane) {
		JScrollPane scrollPane=new JScrollPane(codePane);
		return scrollPane;
	}

	private AKeyPair getKeyPair() {
		return execPanel.getConvex().getKeyPair();
	}
	
	private Address getAddress() {
		return execPanel.getConvex().getAddress();
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
				
				if (btnCompile.isSelected()) {
					ACell compileStep=List.of(Symbols.COMPILE,List.of(Symbols.QUOTE,code));
					Result cr=convex.querySync(compileStep);
					code=cr.getValue();
				}

				String mode = execPanel.getMode();

				long start=Utils.getCurrentTimestamp();
				if (mode.equals("Query")) {
					Address qaddr=getAddress();
					if (qaddr == null) {
						future = convex.query(code,null);
					} else {
						future = convex.query(code, qaddr);
					}
				} else if (mode.equals("Transact")) {
					Address address = getAddress();
					convex.setAddress(address);
					AKeyPair kp=getKeyPair();
					if (kp==null) throw new IllegalStateException("Can't transact without a valid key pair");
					convex.setKeyPair(kp);
					ATransaction trans = Invoke.create(address,0, code);
					SignedData<ATransaction> strans=convex.prepareTransaction(trans);
					
					if (btnTX.isSelected()) {
						addOutputWithHighlight(outputArea,strans.toString()+"\n");
						addOutput(outputArea,"TX Hash: "+strans.getHash()+"\n");
					}
					
					future = convex.transact(strans);
				} else {
					throw new Exception("Unrecognosed REPL mode: " + mode);
				}
				log.trace("Sent message");
				
				handleResult(start,future.get(5000, TimeUnit.MILLISECONDS));
			} catch (ParseException e) {
				outputArea.append(" PARSE ERROR: "+e.getMessage(),Color.RED);
			} catch (TimeoutException t) {
				outputArea.append(" TIMEOUT waiting for result",Color.RED);
			} catch (Exception t) {
				outputArea.append(" ERROR: ",Color.RED);
				outputArea.append(t.getMessage() + "\n");
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
	
	protected void updateHighlight(BaseTextPane pane,int start, int len) {
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
