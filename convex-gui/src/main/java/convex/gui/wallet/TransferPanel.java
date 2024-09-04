package convex.gui.wallet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.Address;
import convex.core.data.prim.AInteger;
import convex.core.util.Utils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.DecimalAmountField;
import convex.gui.components.account.AddressCombo;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TransferPanel extends AbstractGUI {

	protected Convex convex;
	protected TokenInfo token;
	private DecimalAmountField amountField;
	private AddressCombo addressCombo;
	
	/**
	 * Panel for swap display components
	 */
	private JPanel swapPanel;

	private TransferPanel(String title) {
		super(title);
	}

	public TransferPanel(Convex convex, TokenInfo token) {
		this("Token Swap for account "+convex.getAddress());
		this.convex=convex;
		this.token=token;
		
		setLayout(new MigLayout("fill,wrap","[]","[][][grow]"));
		setBorder(Toolkit.createDialogBorder());
		
		swapPanel=new JPanel();
		addTransferComponents(swapPanel);
		add(swapPanel,"align center,span,growx");
	
		// Main action buttons
		ActionPanel actionPanel=new ActionPanel();
		actionPanel.add(new ActionButton("Transfer!",0xe933,e->{
			try {
				Result done = executeTrade();
				if (done==null) {
					return;
				} else if (done.isError()) {
					JOptionPane.showMessageDialog(this, "Transfer failed!\n"+done.getErrorCode()+"\n"+done.getValue());
				} else {
					super.closeGUI();
				}
			} catch (InterruptedException e1) {
				Thread.currentThread().interrupt();
			}
		})); 
		actionPanel.add(new ActionButton("Close",0xe5c9,e->{
			super.closeGUI();
		})); 
		add(actionPanel,"dock south");
	}

	protected void addTransferComponents(JPanel panel) {
		panel.setLayout(new MigLayout("fill,wrap 3","[150][grow]"));
		panel.removeAll();

		panel.add(new JLabel("Destination:"));
		addressCombo=new AddressCombo(); 
		addressCombo.setFont(Toolkit.BIG_FONT);
		panel.add(addressCombo,"span");

		panel.add(new JLabel("Amount:"));
		amountField=new DecimalAmountField(token.getDecimals()); 
		amountField.setFont(Toolkit.BIG_FONT);
		panel.add(amountField,"span");
		amountField.setToolTipText("Input amount of "+token.getSymbol()+" to transfer to the destination account");
		
		panel.validate();
		panel.repaint();
	}
	
	
	protected Result executeTrade() throws InterruptedException {
		AInteger amount=amountField.getAmount();
		if (amount==null) {
			JOptionPane.showMessageDialog(this, "Please specify a valid amount to transfer");
			return null;
		}
		Address target=addressCombo.getAddress();
		String qs;
		if (token.isConvex()) {
			qs="(transfer "+target+" "+amount+")";
		} else {
			qs="(@convex.asset/transfer "+target +" [ "+token.getID()+" "+amount+" ])";
		}
		System.out.println(qs);

		Result r = convex.transactSync(qs);
		return r;
	}

	public static void main(String[] args) throws InterruptedException, IOException, TimeoutException {
		// call to set up Look and Feel
		Toolkit.init();
		InetSocketAddress sa=Utils.toInetSocketAddress("localhost:18888");
		Convex convex=Convex.connect(sa);
		convex.setAddress(Address.create(11));
		new TransferPanel(convex,TokenInfo.getFungible(convex,"currency.USDF")).run();
	}

}
