package convex.gui.wallet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.AInteger;
import convex.core.util.Utils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.BalanceLabel;
import convex.gui.components.DecimalAmountField;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SwapPanel extends AbstractGUI {

	protected Convex convex;
	protected TokenInfo token1;
	protected TokenInfo token2;
	private DecimalAmountField amountField;
	private JPanel swapPanel;
	private JPanel tradePanel;
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
		
		tradePanel=new JPanel();
		tradePanel.setLayout(new MigLayout("fill,wrap 3","[150][grow]"));
		tradePanel.add(new JLabel("You receive:"));
		receiveLabel = new BalanceLabel();
		tradePanel.add(receiveLabel);
		add(tradePanel);
		
		// Main action buttons
		ActionPanel actionPanel=new ActionPanel();
		actionPanel.add(new ActionButton("Trade!",0xe933,e->{
			executeTrade();
			super.closeGUI();
		})); 
		actionPanel.add(new ActionButton("Close",0xe5c9,e->{
			super.closeGUI();
		})); 
		actionPanel.add(new ActionButton("Refresh",0xe5d5,e->{
			refreshRates();
		})); 
		add(actionPanel,"dock south");
	}

	protected void addSwapComponents() {
		swapPanel.setLayout(new MigLayout("fill,wrap 3","[150][grow]"));
		swapPanel.removeAll();

		amountField=new DecimalAmountField(token1.getDecimals()); 
		amountField.setFont(amountField.getFont().deriveFont(30f));
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
		
		swapPanel.add(new JLabel("From:"));
		swapPanel.add(new TokenButton(token1),"wrap,grow");
		JButton switchButton=new JButton(SymbolIcon.get(0xe8d5,Toolkit.SMALL_ICON_SIZE));
		switchButton.addActionListener(e-> {
			TokenInfo temp=token1;
			token1=token2;
			token2=temp;
			addSwapComponents();
			refreshRates();
		});
		swapPanel.add(new JLabel()); // spacer
		swapPanel.add(switchButton,"span");
		swapPanel.add(new JLabel("To:"));
		swapPanel.add(new TokenButton(token2),"wrap,grow");
		swapPanel.validate();
		swapPanel.repaint();
	}
	

	protected void refreshRates() {
		ACell torus=TokenInfo.getTorusAddress(convex);
		receiveLabel.setDecimals(token2.getDecimals());
		AInteger amount=amountField.getAmount();
		String qs;
		if (token1.isConvex()) {
			System.err.println(amount +" :dec "+token1.getDecimals());
			qs="("+torus+"/sell-cvx "+token2.getID()+" "+amount+")";
		} else if (token2.isConvex()) {
			qs="("+torus+"/sell-quote "+token1.getID()+" "+amount+")";
		} else {
			qs="("+torus+"/sell-quote "+token1.getID()+" "+amount+" "+token2.getID()+")";
		}
		try {
			convex.query(qs).thenAccept(r->{
				ACell val=r.getValue();
				if (val instanceof AInteger) {
					receiveLabel.setBalance((AInteger) val);
				} else {
					receiveLabel.setBalance(null);
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	protected void executeTrade() {
		ACell torus=TokenInfo.getTorusAddress(convex);
		receiveLabel.setDecimals(token2.getDecimals());
		AInteger amount=amountField.getAmount();
		String qs;
		if (token1.isConvex()) {
			System.err.println(amount +" :dec "+token1.getDecimals());
			qs="("+torus+"/sell-cvx "+token2.getID()+" "+amount+")";
		} else if (token2.isConvex()) {
			qs="("+torus+"/sell-tokens "+token1.getID()+" "+amount+")";
		} else {
			qs="("+torus+"/sell "+token1.getID()+" "+amount+" "+token2.getID()+")";
		}
		System.out.println(qs);
		try {
			convex.transact(qs).thenAccept(r->{
				ACell val=r.getValue();
				System.out.println(r);
				if (val instanceof AInteger) {
					receiveLabel.setBalance((AInteger) val);
				} else {
					receiveLabel.setBalance(null);
				}
			});
		} catch (IOException | TimeoutException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws InterruptedException, IOException, TimeoutException {
		// call to set up Look and Feel
		Toolkit.init();
		InetSocketAddress sa=Utils.toInetSocketAddress("localhost:18888");
		Convex convex=Convex.connect(sa);
		convex.setAddress(Address.create(11));
		new SwapPanel(convex,TokenInfo.getFungible(convex,"currency.USDF"),TokenInfo.convexCoin()).run();
	}

}
