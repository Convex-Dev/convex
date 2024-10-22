package convex.gui.panels;
		
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
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
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.SignedData;
import convex.core.exceptions.ParseException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.data.Symbols;
import convex.core.util.Utils;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.BaseTextPane;
import convex.gui.components.CodePane;
import convex.gui.components.account.AccountChooserPanel;
import convex.gui.utils.CVXHighlighter;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Panel presenting a general purpose REPL terminal
 */
@SuppressWarnings("serial")
public class REPLPanel extends JPanel {

	protected final CodePane input;
	protected final CodePane output;
	protected final JScrollPane inputScrollPane;
	protected final JScrollPane outputScrollPane;
	private final JButton btnRun;
	private final JButton btnClear;
	private final JButton btnInfo;
	private final JCheckBox btnResults;
	private final JCheckBox btnTiming;
	private final JCheckBox btnCompile;
	private final JCheckBox btnTX;
	
	private final ArrayList<String> history=new ArrayList<>();
	private int historyPosition=0;

	private InputListener inputListener=new InputListener();

	private Font OUTPUT_FONT=Toolkit.MONO_FONT.deriveFont(16f*Toolkit.SCALE);
	private Font INPUT_FONT=Toolkit.MONO_FONT.deriveFont(20f*Toolkit.SCALE);
	private Color DEFAULT_OUTPUT_COLOR=Color.LIGHT_GRAY;
	
	private JPanel actionPanel;

	private AccountChooserPanel execPanel;

	private final Convex convex;
	private JSplitPane splitPane;

	private static final Logger log = LoggerFactory.getLogger(REPLPanel.class.getName());

	public void setInput(String s) {
		input.setText(s);
		updateHighlight();
	}

	@Override
	public void setVisible(boolean value) {
		super.setVisible(value);
		if (value) input.requestFocusInWindow();
	}

	protected void handleResult(long start,Result r) {
		if (btnTiming.isSelected()) {
			output.append("Completion time: " + (Utils.getCurrentTimestamp()-start) + " ms\n");
		}
		if (r==null) {
			output.append(" No result??\n",Color.ORANGE);
			return;
		}
		if (btnResults.isSelected()) {
			showResultValue((ACell)r);
	    } else if (r.isError()) {
			handleError(r.getErrorCode(),r.getValue(),r.getTrace());
		} else {
			showResultValue((ACell)r.getValue());
		}
		execPanel.updateBalance();
	}

	protected void showResultValue(ACell m) {
		AString s=RT.print(m);
		String resultString=(s==null)?"Print limit exceeded!":s.toString();
		
		output.append("=> ", Color.CYAN);
		int start=output.docLength();
		output.append(resultString + "\n");
		int end=output.docLength();
		updateHighlight(output,start,end);
		
		output.setCaretPosition(end);
	}
	
	protected void handleError(ACell code, ACell msg, AVector<AString> trace) {
		output.append(" Exception: " + code + " "+ msg+"\n",Color.ORANGE);
		if (trace!=null) for (AString s: trace) {
			output.append(" - "+s.toString()+"\n",Color.PINK);
		}
	}
	
	private void addOutputWithHighlight(BaseTextPane pane, String text) {
		Document d=pane.getDocument();
		int start = d.getLength();
		pane.append(text,DEFAULT_OUTPUT_COLOR);	
		int end=d.getLength();
		if (end>start) updateHighlight(pane,start,end);
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

		output = new CodePane();
		output.setEditable(false);
		output.setFont(OUTPUT_FONT);
		//outputArea.setForeground(Color.GREEN);
		output.setBackground(new Color(10,10,10));
		output.setToolTipText("Output from transaction execution");
		//DefaultCaret caret = (DefaultCaret)(outputArea.getCaret());
		//caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		outputScrollPane=wrapScrollPane(output);
		splitPane.setLeftComponent(outputScrollPane);

		input = new CodePane();
		input.setFont(INPUT_FONT);
		input.getDocument().addDocumentListener(inputListener);
		input.addKeyListener(inputListener);
		input.setToolTipText("Input commands here (Press Enter at the end of input to send)");
		inputScrollPane=wrapScrollPane(input);
		//inputArea.setForeground(Color.GREEN);

		splitPane.setRightComponent(inputScrollPane);
		
		// stop CTRL+arrow losing focus
		setFocusTraversalKeysEnabled(false);
		
		// BOTTOM ACTION PANEL
		
		actionPanel = new ActionPanel();
		add(actionPanel, "dock south");

		btnRun = new ActionButton("Run",0xe1c4,e -> {
			sendMessage(input.getText());
			input.requestFocus();
		});
		actionPanel.setToolTipText("Run the current command from the input pane");
		actionPanel.add(btnRun);
		
		btnClear = new ActionButton("Clear",0xe9d5,e -> {
			output.setText("");
			input.setText("");
			input.requestFocus();
		});
		btnClear.setToolTipText("Clear the input and output");
		actionPanel.add(btnClear);

		btnInfo = new ActionButton("Connection Info",0xe88e,e -> {
			StringBuilder sb=new StringBuilder();
			if (convex instanceof ConvexRemote) {
				sb.append("Remote host: " + convex.getHostAddress() + "\n");
			}
			try {
				sb.append("Sequence:    " + convex.getSequence() + "\n");
			} catch (Exception e1) {
				log.info("Failed to get sequence number");
			}
			sb.append("Account:     " + RT.print(convex.getAddress()) + "\n");
			sb.append("Public Key:  " + RT.toString(convex.getAccountKey()) + "\n");
			sb.append("Connected:   " + convex.isConnected()+"\n");

			String infoString = sb.toString();
			JOptionPane.showMessageDialog(this, infoString);
		});
		actionPanel.setToolTipText("Show diagnostic information for the Convex connection");
		actionPanel.add(btnInfo);
		
		btnTX=new JCheckBox("Show transaction");
		btnTX.setToolTipText("Tick to show details of the transaction sent, including signature and transaction hash.");
		actionPanel.add(btnTX);
		
		btnResults=new JCheckBox("Full Results");
		btnResults.setToolTipText("Tick to show full Result record returned from peer.");
		actionPanel.add(btnResults);
		
		btnTiming=new JCheckBox("Show Timing");
		btnTiming.setToolTipText("Tick to receive execution time report after each transaction.");
		actionPanel.add(btnTiming);
		
		btnCompile=new JCheckBox("Precompile");
		btnCompile.setToolTipText("Tick to compile code before sending transaction. Usually reduces juice costs at the cost of slightly slower execution time.");
		btnCompile.setSelected(convex.isPreCompile());
		actionPanel.add(btnCompile);

		
		// Get initial focus in REPL input area
		addComponentListener(new ComponentAdapter() {
			public void componentShown(ComponentEvent ce) {
				input.requestFocusInWindow();
			}
		});
	}

	private JScrollPane wrapScrollPane(CodePane codePane) {
		JScrollPane scrollPane=new JScrollPane(codePane);
		return scrollPane;
	}

	protected AKeyPair getKeyPair() {
		return execPanel.getConvex().getKeyPair();
	}
	
	protected Address getAddress() {
		return execPanel.getConvex().getAddress();
	}

	private void sendMessage(String s) {
		if (s.isBlank()) return;
		output.append(s);
		output.append("\n");
		input.setText("");
		
		history.add(s);
		historyPosition=history.size();

		SwingUtilities.invokeLater(() -> {
			input.setText("");
			long start=Utils.getCurrentTimestamp();
			try {
				AList<ACell> forms = Reader.readAll(s);
				ACell code = (forms.count()==1)?forms.get(0):forms.cons(Symbols.DO);
				CompletableFuture<Result> future;
				
				convex.setPreCompile(btnCompile.isSelected());

				String mode = execPanel.getMode();

				if (mode.equals("Query")) {
					Address qaddr=getAddress();
					future = convex.query(code, qaddr);
				} else if (mode.equals("Transact")) {
					future = CompletableFuture.supplyAsync(()->{
						try {
							SignedData<ATransaction> strans=convex.prepareTransaction(code);					
							if (btnTX.isSelected()) {
								addOutputWithHighlight(output,strans.toString()+"\n");
								output.append("TX Hash: "+strans.getHash()+"\n");
							}
							return strans;
						} catch(Exception e) {
							throw Utils.sneakyThrow(e);
						}
					}).thenCompose(strans->convex.transact(strans));
							
					
				} else if (mode.equals("Prepare")) {
					SignedData<ATransaction> strans=convex.prepareTransaction(code);	
					convex.clearSequence();
					addOutputWithHighlight(output,strans.toString()+"\n");
					return;
				} else {
					throw new Exception("Unrecognosed REPL mode: " + mode);
				}
				log.trace("Sent message");
				
				future.handleAsync((m,e)->{
					if (e!=null) {
						e.printStackTrace();
						m=Result.fromException(e);
					} 
					handleResult(start,m);
					Toolkit.scrollToBottom(outputScrollPane);
					return m;
				});
			} catch (ResultException e) {
				handleResult(start,e.getResult());
			} catch (ParseException e) {
				output.append(" PARSE ERROR: "+e.getMessage()+"\n",Color.RED);
			} catch (TimeoutException t) {
				output.append(" TIMEOUT waiting for result\n",Color.RED);
			} catch (IllegalStateException t) {
				// General errors we understand
				output.append(" ERROR: ",Color.RED);
				output.append(t.getMessage() + "\n"); 
			} catch (Exception t) {
				// Something bad.....
				output.append(" ERROR: ",Color.RED);
				output.append(t.getMessage() + "\n"); 
				t.printStackTrace();
			}
		});
	}

	
	boolean highlighting=false;
	protected void updateHighlight() {
		int len=input.getDocument().getLength();
		if (!highlighting) {
			highlighting=true;
			updateHighlight(input,0,len);
		}
	}
	
	protected void updateHighlight(BaseTextPane pane,int start, int end) {
		SwingUtilities.invokeLater(()->{
			CVXHighlighter.highlight(pane,start,end);
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
							String s=input.getText();
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
					Document doc=input.getDocument();
					int docLen=doc.getLength();
					if (e.isControlDown()||e.isShiftDown()) {
						doc.insertString(docLen, "\n",SimpleAttributeSet.EMPTY);
					} else {
						int off = input.getCaretPosition();
							
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
