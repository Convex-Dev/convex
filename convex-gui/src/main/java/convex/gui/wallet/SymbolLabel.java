package convex.gui.wallet;

import java.awt.Font;

import convex.core.data.ACell;
import convex.gui.components.CodeLabel;
import convex.gui.utils.Toolkit;

/**
 * Simple label for a token Symbol text
 */
@SuppressWarnings("serial")
public class SymbolLabel extends CodeLabel {

	private String symbolName;

	public SymbolLabel(TokenInfo token) {
		ACell tokenID=token.getID();
		symbolName=token.getSymbol();
		setFont(Toolkit.MONO_FONT.deriveFont(Font.BOLD));
		setText(symbolName);
		if (tokenID==null) {
			setToolTipText("This is the native Convex Coin");
		} else {
			setToolTipText(symbolName+" is a fungible token with ID: "+tokenID);
		}
	}

	public String getSymbolName() {
		return symbolName;
	}
}
