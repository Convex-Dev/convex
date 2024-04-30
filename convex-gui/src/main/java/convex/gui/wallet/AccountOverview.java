package convex.gui.wallet;

import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.lang.RT;
import convex.gui.components.BalanceLabel;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AccountOverview extends JPanel {

	final BalanceLabel balance=new BalanceLabel();
	

	public AccountOverview(Convex convex) {
		setLayout(new MigLayout("wrap 2","[][]"));
		Font font=this.getFont();
		
		
		add(new JLabel(SymbolIcon.get(0xe7fd,160)),"dock west");
		
		add(new JLabel(SymbolIcon.get(0xe80b,Toolkit.ICON_SIZE))); // globe
		JLabel addressLabel=new JLabel(RT.toString(convex.getAddress()));
		addressLabel.setFont(font.deriveFont(60f));
		add(addressLabel);
		
		
		add(new JLabel(Toolkit.CONVEX)); // convex icon
		// add(new JLabel("Convex Coins"));
		balance.setFont(Toolkit.DEFAULT_FONT.deriveFont(60f));
		balance.setBalance(convex); 
		add(balance);
	}
}
