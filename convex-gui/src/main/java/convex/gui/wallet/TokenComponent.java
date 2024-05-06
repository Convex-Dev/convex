package convex.gui.wallet;

import java.awt.Font;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.gui.components.ActionButton;
import convex.gui.components.BalanceLabel;
import convex.gui.components.CodeLabel;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenComponent extends JPanel {

	protected Convex convex;
	protected BalanceLabel balanceLabel;
	private TokenInfo token;
	
	private static SymbolIcon DEFAULT_ICON=SymbolIcon.get(0xf041, Toolkit.ICON_SIZE);

	public TokenComponent(Convex convex, TokenInfo token) {
		this.convex=convex;
		this.token=token;
		
		this.setLayout(new MigLayout("","["+(Toolkit.ICON_SIZE+10)+"][200][300][300]push"));
		this.setBorder(Toolkit.createEmptyBorder(20));
		
		ACell tokenID=token.getID();
		Icon icon=getIcon(token);
		add(new JButton(icon));
		
		String symbolName=token.getSymbol();
		CodeLabel symLabel=new CodeLabel(symbolName);
		symLabel.setFont(Toolkit.MONO_FONT.deriveFont(Font.BOLD));
		symLabel.setToolTipText(symbolName+" has Token ID: "+tokenID);
		add(symLabel);
		
		balanceLabel = new BalanceLabel();
		balanceLabel.setDecimals(token.getDecimals());
				balanceLabel.setFont(Toolkit.MONO_FONT);
		balanceLabel.setBalance(0);
		balanceLabel.setToolTipText("Account balance for "+symbolName);
		add(balanceLabel,"align right");
		
		// Action buttons
		JPanel actions=new JPanel();
		actions.add(ActionButton.build(0xe933,e->{
			// Token swap
			new SwapPanel(convex,token).run();
		},"Open token swap window for this token"));
		actions.add(ActionButton.build(0xe5d5,e->{
			refresh(convex);
		},"Refresh token info")); 
		actions.add(ActionButton.build(0xe872,e->{
			WalletPanel.model.removeElement(token);
		},"Remove token from tracked list"));

		
		add(actions,"dock east");
		SwingUtilities.invokeLater(()->refresh(convex));
	}

	protected Icon getIcon(TokenInfo token) {
		switch (token.getSymbol()) {
		   case "USDF": return  SymbolIcon.get(0xe227, Toolkit.ICON_SIZE);
		   case "GBPF": return  SymbolIcon.get(0xeaf1, Toolkit.ICON_SIZE);
		}
		
		ACell tokenID=token.getID();
		return (tokenID==null)?Toolkit.CONVEX:DEFAULT_ICON;
	}

	public void refresh(Convex convex) {
		AInteger bal=token.getBalance(convex);
		if (bal!=null) {
			balanceLabel.setBalance(bal);
		} else {
			balanceLabel.setBalance(null); 
		}
	}
}
