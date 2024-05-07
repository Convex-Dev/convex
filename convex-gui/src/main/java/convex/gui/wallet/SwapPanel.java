package convex.gui.wallet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import convex.api.Convex;
import convex.core.data.Address;
import convex.core.data.prim.AInteger;
import convex.core.data.ACell;
import convex.core.util.Utils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
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

	private SwapPanel(String title) {
		super(title);
	}

	public SwapPanel(Convex convex, TokenInfo token1, TokenInfo token2) {
		this("Token Swap for account "+convex.getAddress());
		this.convex=convex;
		this.token1=token1;
		this.token2=token2;
		
		setLayout(new MigLayout("wrap 3"));
		
		amountField=new DecimalAmountField(token1.getDecimals()); 
		amountField.setFont(amountField.getFont().deriveFont(30f));
		add(new JLabel("Amount:"));
		add(amountField,"wrap");
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
		
		add(new JLabel("From:"));
		add(new TokenComponent(convex,token1),"wrap");
		add(new JButton(SymbolIcon.get(0xe8d5,Toolkit.SMALL_ICON_SIZE)),"align center,span");
		add(new JLabel("To:"));
		add(new TokenComponent(convex,token2),"wrap");
		
		ActionPanel actionPanel=new ActionPanel();
		
		actionPanel.add(new ActionButton("Trade!",0xe933,e->{
			super.closeGUI();
		})); 
		actionPanel.add(new ActionButton("Close",0xe5c9,e->{
			super.closeGUI();
		})); 
		add(actionPanel,"dock south");
	}
	

	protected void refreshRates() {
		ACell torus=TokenInfo.getTorusAddress(convex);
		AInteger amount=amountField.getAmount();
		String qs;
		if (token1.isConvex()) {
			qs="("+torus+"/sell-cvx "+token2.getID()+" "+amount+")";
		} else if (token2.isConvex()) {
			qs="("+torus+"/sell-quote "+token1.getID()+" "+amount+")";
		} else {
			qs="("+torus+"/sell-quote "+token1.getID()+" "+amount+" "+token2.getID()+")";
		}
		try {
			convex.query(qs).thenAccept(r->{
				System.err.println(r.getValue());
			});
		} catch (IOException e) {
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
