package convex.gui.wallet;

import java.awt.Font;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.lang.Reader;
import convex.gui.components.ActionButton;
import convex.gui.components.BalanceLabel;
import convex.gui.utils.Async;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenComponent extends JPanel {

	protected Convex convex;
	protected BalanceLabel balanceLabel;
	private TokenInfo token;
	
	static final Font BALANCE_FONT=Toolkit.BIG_FONT;
	TokenButton tokenButton;

	/**
	 * Creates a component for a tracked token
	 * @param convex Connection used for balances and actions
	 * @param network Network the token is tracked on
	 * @param token Token to show
	 */
	public TokenComponent(Convex convex, NetworkState network, TokenInfo token) {
		this.convex=convex;
		this.token=token;
		
		this.setLayout(new MigLayout("","["+(Toolkit.ICON_SIZE+100)+"][400]"));
		this.setBorder(Toolkit.createEmptyBorder(20));
		
		tokenButton=new TokenButton(token);
		add(tokenButton);
		
		balanceLabel = new BalanceLabel();
		balanceLabel.setDecimals(token.getDecimals());
		balanceLabel.setFont(BALANCE_FONT);
		balanceLabel.setToolTipText("Account balance for "+token.getSymbol());
		add(balanceLabel,"align right");
		
		// Action buttons
		JPanel actions=new JPanel();
		actions.add(ActionButton.build(0xe88e,e->{
			JOptionPane.showMessageDialog(this, new TokenInfoPanel(convex,token),"Token Info", JOptionPane.INFORMATION_MESSAGE);
		},"Show token information"));

		actions.add(ActionButton.build(0xe163,e->{
			new TransferPanel(convex,token).runNonModal(this);
		},"Send this token to another account"));

		actions.add(ActionButton.build(0xe933,e->{
			// Token swap
			TokenInfo with= network.otherToken(token);
			if (with!=null) {
				openSwap(with);
				return;
			}
			String tok=JOptionPane.showInputDialog(this, "Specify a token to swap with");
			if (tok==null) return;
			Async.whileBusy(e, Async.background(()->TokenInfo.get(convex, Reader.read(tok)))
				.thenAcceptAsync(this::openSwap, Async.EDT));
		},"Open token swap window for this token"));
		actions.add(ActionButton.build(0xe5d5,e->{
			refresh(convex);
		},"Refresh token info")); 
		actions.add(ActionButton.build(0xe872,e->{
			network.untrack(token);
		},"Remove token from tracked list"));
		
		add(actions,"dock east");
		
		
		refresh(convex);
	}

	/** Opens a swap of this token for another, if found */
	private void openSwap(TokenInfo with) {
		if (with==null) {
			JOptionPane.showMessageDialog(this, "Can't find token");
			return;
		}
		new SwapPanel(convex,token,with).runNonModal(this);
	}

	/** Refreshes the balance shown. Safe from any thread. */
	public void refresh(Convex convex) {
		balanceLabel.show(token.getBalance(convex));
	}
}
