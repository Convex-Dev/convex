package convex.gui.wallet;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.data.ACell;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class TokenInfoPanel extends JPanel {

	public TokenInfoPanel(Convex convex, TokenInfo token) {
		setLayout(new MigLayout("wrap 2"));
		
		add(new JLabel("Symbol: "));
		add(new JLabel(token.getSymbol()),"wrap");

		add(new JLabel("ID: "));
		ACell id=token.getID();
		String idString=id==null? "(Native Coin)":id.print().toString();
		add(new JLabel(idString),"wrap");

	}

}
