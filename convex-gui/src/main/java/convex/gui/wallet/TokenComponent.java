package convex.gui.wallet;

import javax.swing.JPanel;

import convex.api.Convex;
import convex.gui.components.ActionButton;
import convex.gui.components.BalanceLabel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenComponent extends JPanel {

	protected Convex convex;
	protected BalanceLabel balanceLabel;
	private TokenInfo token;
	TokenButton tokenButton;

	public TokenComponent(Convex convex, TokenInfo token) {
		this.convex=convex;
		this.token=token;
		
		this.setLayout(new MigLayout("","["+(Toolkit.ICON_SIZE+100)+"][300][300]push"));
		this.setBorder(Toolkit.createEmptyBorder(20));
		
		tokenButton=new TokenButton(token);
		add(tokenButton);
		
		balanceLabel = new BalanceLabel();
		balanceLabel.setDecimals(token.getDecimals());
		balanceLabel.setFont(Toolkit.BIG_MONO_FONT);
		balanceLabel.setToolTipText("Account balance for "+token.getSymbol());
		add(balanceLabel,"align right");
		
		// Action buttons
		JPanel actions=new JPanel();
		actions.add(ActionButton.build(0xe88e,e->{
			// Token info TODO
		},"Show token information"));

		actions.add(ActionButton.build(0xe8b7,e->{
			// Token send TODO
		},"Send this token to another account"));

		actions.add(ActionButton.build(0xe933,e->{
			// Token swap
			new SwapPanel(convex,token,WalletPanel.getDefaultToken()).run();
		},"Open token swap window for this token"));
		actions.add(ActionButton.build(0xe5d5,e->{
			refresh(convex);
		},"Refresh token info")); 
		actions.add(ActionButton.build(0xe872,e->{
			WalletPanel.model.removeElement(token);
		},"Remove token from tracked list"));

		
		add(actions,"dock east");
		refresh(convex);
	}

	public void refresh(Convex convex) {
		try {
			token.getBalance(convex).thenAccept(bal->{
				if (bal!=null) {
					balanceLabel.setBalance(bal);
				} else {
					balanceLabel.setBalance(null); 
				}
			}).exceptionally(e->{
				e.printStackTrace();
				balanceLabel.setBalance(null);
				return null;
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
