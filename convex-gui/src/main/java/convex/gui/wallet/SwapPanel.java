package convex.gui.wallet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.cvm.Address;
import convex.core.data.prim.AInteger;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.BalanceLabel;
import convex.gui.components.DecimalAmountField;
import convex.gui.utils.Async;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.net.IPUtils;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SwapPanel extends AbstractGUI {

	protected Convex convex;
	protected TokenInfo token1;
	protected TokenInfo token2;
	private DecimalAmountField amountField;
	
	/**
	 * Panel for swap display components
	 */
	private JPanel swapPanel;
	private BalanceLabel receiveLabel;

	private SwapPanel(String title) {
		super(title);
	}

	public SwapPanel(Convex convex, TokenInfo token1, TokenInfo token2) {
		this("Token Swap for account "+convex.getAddress());
		this.convex=convex;
		this.token1=token1;
		this.token2=token2;
		
		setLayout(new MigLayout("fill,wrap","[]","[][][grow]"));
		setBorder(Toolkit.createDialogBorder());
		
		swapPanel=new JPanel();
		addSwapComponents();
		add(swapPanel,"align center,span,growx");
	
		// Main action buttons
		ActionPanel actionPanel=new ActionPanel();
		actionPanel.add(new ActionButton("Trade!",0xe933,e->{
			CompletableFuture<Result> trade=executeTrade();
			if (trade==null) {
				JOptionPane.showMessageDialog(this, "Please specify a valid amount to trade");
				return;
			}
			Async.whileBusy(e, trade.thenAcceptAsync(r->{
				if (r.isError()) {
					JOptionPane.showMessageDialog(this, Toolkit.plainMessage("Trade failed!\n"+r.getErrorCode()+"\n"+r.getValue()));
				} else {
					super.closeGUI();
				}
			}, Async.EDT));
		}));
		actionPanel.add(new ActionButton("Close",0xe5c9,e->{
			super.closeGUI();
		})); 
		actionPanel.add(new ActionButton("Refresh",0xe5d5,e->{
			refreshRates();
		})); 
		add(actionPanel,"dock south");
		refreshRates();
	}

	protected void addSwapComponents() {
		swapPanel.removeAll();
		swapPanel.setLayout(new MigLayout("fill,wrap 3"));
		
		swapPanel.add(new JLabel("Amount:"));
		amountField=new DecimalAmountField(token1.getDecimals()); 
		amountField.setFont(Toolkit.BIG_FONT);
		swapPanel.add(new TokenButton(token1));
		swapPanel.add(amountField,"span");
		amountField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refreshRates();
			}
			@Override
			public void removeUpdate(DocumentEvent e) {
				refreshRates();
			}
			@Override
			public void changedUpdate(DocumentEvent e) {}
		});
		amountField.setToolTipText("Input amount of "+token1.getSymbol()+" to swap into "+token2.getSymbol());
				
		JButton switchButton=new JButton(SymbolIcon.get(0xe8d5,Toolkit.ICON_SIZE));
		switchButton.addActionListener(e-> {
			TokenInfo temp=token1;
			token1=token2;
			token2=temp;
			
			String amt=receiveLabel.getText();
			System.err.println("Switching amount: "+amt);
			addSwapComponents();
			amountField.setText(amt);
			System.err.println("Amount Set: "+amountField.getAmount());
			refreshRates();
		});
		
		swapPanel.add(new JLabel()); // spacer
		swapPanel.add(switchButton,"center,span");
		swapPanel.add(new JLabel("To:"));
		swapPanel.add(new TokenButton(token2));
		
		// Receive amount line
		receiveLabel = new BalanceLabel();
		receiveLabel.setFont(Toolkit.BIG_FONT);
		receiveLabel.setDecimals(token2.getDecimals());
		receiveLabel.setToolTipText("Amount you will receive when swap occurs");
		swapPanel.add(receiveLabel);

		swapPanel.validate();
		swapPanel.repaint();
	}
	

	protected void refreshRates() {
		receiveLabel.setDecimals(token2.getDecimals());
		AInteger amount=amountField.getAmount();
		if (amount==null) {
			receiveLabel.setText("-");
			return;
		}
		TokenInfo from=token1, to=token2;
		receiveLabel.showResult(Async.background(()->{
			ACell torus=TokenInfo.getTorusAddress(convex);
			if (from.isConvex()) return convex.querySync("("+torus+"/sell-cvx "+to.getID()+" "+amount+")");
			if (to.isConvex()) return convex.querySync("("+torus+"/sell-quote "+from.getID()+" "+amount+")");
			return convex.querySync("("+torus+"/sell-quote "+from.getID()+" "+amount+" "+to.getID()+")");
		}));
	}

	/**
	 * Starts the trade for the amount entered
	 * @return Future for the trade result, or null if no valid amount was entered
	 */
	protected CompletableFuture<Result> executeTrade() {
		AInteger amount=amountField.getAmount();
		if (amount==null) return null;
		TokenInfo from=token1, to=token2;
		return Async.background(()->{
			ACell torus=TokenInfo.getTorusAddress(convex);
			if (from.isConvex()) return convex.transactSync("("+torus+"/sell-cvx "+to.getID()+" "+amount+")");
			if (to.isConvex()) return convex.transactSync("("+torus+"/sell-tokens "+from.getID()+" "+amount+")");
			return convex.transactSync("("+torus+"/sell "+from.getID()+" "+amount+" "+to.getID()+")");
		});
	}

	public static void main(String[] args) throws InterruptedException, IOException, TimeoutException {
		// call to set up Look and Feel
		Toolkit.init();
		InetSocketAddress sa=IPUtils.toInetSocketAddress("localhost:18888");
		Convex convex=Convex.connect(sa);
		convex.setAddress(Address.create(11));
		new SwapPanel(convex,TokenInfo.getFungible(convex,"currency.USDF"),TokenInfo.convexCoin()).run();
	}

}
