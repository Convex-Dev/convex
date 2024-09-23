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
import convex.gui.components.AbstractGUI;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.BalanceLabel;
import convex.gui.components.DecimalAmountField;
import convex.gui.components.account.AddressCombo;
import convex.gui.models.ComboModel;
import convex.gui.utils.Toolkit;
import convex.net.IPUtils;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TransferPanel extends AbstractGUI {

	protected Convex convex;
	protected TokenInfo token;
	protected BalanceLabel balanceLabel;
	private DecimalAmountField amountField;
	private AddressCombo addressCombo;
	private Address address;
	
	private static ComboModel<Address> model= new ComboModel<>();
	
	/**
	 * Panel for swap display components
	 */
	private JPanel swapPanel;

	private TransferPanel(String title) {
		super(title);
	}

	public TransferPanel(Convex convex, TokenInfo token) {
		this("Token Transfer for account "+convex.getAddress());
		this.convex=convex;
		this.token=token;
		address=convex.getAddress();
		if (address==null) {
			throw new IllegalStateException("Must be a valid address to transfer from");
		}
		model.ensureContains(address);
		
		setLayout(new MigLayout("fill,wrap","[]","[][][grow]"));
		setBorder(Toolkit.createDialogBorder());
		
		swapPanel=new JPanel();
		addTransferComponents(swapPanel);
		add(swapPanel,"align center,span,growx");
	
		// Main action buttons
		ActionPanel actionPanel=new ActionPanel();
		actionPanel.add(new ActionButton("Transfer!",0xe933,e->{
			try {
				Result done = executeTransfer();
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
		panel.removeAll();
		panel.setLayout(new MigLayout("fill,wrap 3","[150][grow]"));

		panel.add(new JLabel("Amount to transfer: "));
		panel.add(new TokenButton(token));
		amountField=new DecimalAmountField(token.getDecimals()); 
		amountField.setFont(Toolkit.BIG_FONT);
		panel.add(amountField,"span");

		panel.add(new JLabel("Balance:"),"span 2");
		balanceLabel=new BalanceLabel();
		balanceLabel.setDecimals(token.getDecimals());
		balanceLabel.setBalance(token.getBalance(convex).join());
		balanceLabel.setToolTipText("Current balance available in account "+address);
		panel.add(balanceLabel,"span");

		
		panel.add(new JLabel("Destination account: "),"span 2");
		addressCombo=new AddressCombo(model); 
		addressCombo.setFont(Toolkit.BIG_FONT);
		addressCombo.setToolTipText("Destination account that will receive asset after transfer");
		panel.add(addressCombo,"span");

		amountField.setToolTipText("Input amount of "+token.getSymbol()+" to transfer to the destination account");
		
		panel.validate();
		panel.repaint();
	}
	
	
	protected Result executeTransfer() throws InterruptedException {
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
		if (!r.isError()) model.ensureContains(target);
		return r;
	}

	public static void main(String[] args) throws InterruptedException, IOException, TimeoutException {
		// call to set up Look and Feel
		Toolkit.init();
		InetSocketAddress sa=IPUtils.toInetSocketAddress("localhost:18888");
		Convex convex=Convex.connect(sa);
		convex.setAddress(Address.create(11));
		new TransferPanel(convex,TokenInfo.getFungible(convex,"currency.USDF")).run();
	}

}
