package convex.gui.wallet;

import javax.swing.Icon;
import javax.swing.JButton;

import convex.core.data.ACell;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class TokenButton extends JButton {

	private static SymbolIcon DEFAULT_ICON=SymbolIcon.get(0xf041, Toolkit.ICON_SIZE);

	public TokenButton(TokenInfo token) {
		Icon icon=getIcon(token);
		this.setFocusable(false);
		setIcon(icon);
	}

	protected Icon getIcon(TokenInfo token) {
		switch (token.getSymbol()) {
		   case "USDF": return  SymbolIcon.get(0xe227, Toolkit.ICON_SIZE);
		   case "GBPF": return  SymbolIcon.get(0xeaf1, Toolkit.ICON_SIZE);
		}
		
		ACell tokenID=token.getID();
		return (tokenID==null)?Toolkit.CONVEX:DEFAULT_ICON;
	}
}
