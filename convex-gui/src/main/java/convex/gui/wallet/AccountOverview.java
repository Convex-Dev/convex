package convex.gui.wallet;

import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.lang.RT;
import convex.gui.components.BalanceLabel;
import convex.gui.utils.SymbolIcon;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AccountOverview extends JPanel {

	final BalanceLabel balance=new BalanceLabel();
	

	public AccountOverview(Convex convex) {
		setLayout(new MigLayout("fill","","push[][]"));
		Font font=this.getFont();
		
		String addrString=RT.toString(convex.getAddress());
		
		{ //Account Label
			JLabel accountLabel=new JLabel(SymbolIcon.get(0xe7fd,200));
			accountLabel.setHorizontalAlignment(JLabel.CENTER);
			accountLabel.setHorizontalTextPosition(JLabel.CENTER);
			accountLabel.setVerticalTextPosition(JLabel.BOTTOM);
			accountLabel.setIconTextGap(0);
			accountLabel.setFont(font.deriveFont(40f));
			add(accountLabel,"dock west");
		}
//		add(new JLabel(SymbolIcon.get(0xe80b,Toolkit.ICON_SIZE))); // globe
//		JLabel addressLabel=new JLabel();
//		addressLabel.setFont(font.deriveFont(60f));
//		add(addressLabel);
		
		// headings
		add(new JLabel("Name"));
		add(new JLabel("Address"));
		add(new JLabel("Convex Coins"),"wrap");
		//add(new JLabel("Identicon"),"wrap");
		
		Font bigfont=font.deriveFont(40f);
		
		{ // Name label
			JLabel nl=new JLabel("anonymous");
			nl.setFont(bigfont);
			add(nl);
		}
		
		{ // Address label
		JLabel al=new JLabel(addrString);
		al.setFont(bigfont);
		add(al);
		}

		{ // Coin Balance
			//add(new JLabel(Toolkit.CONVEX)); // convex icon
			balance.setFont(bigfont);
			balance.setBalance(convex); 
			add(balance);
		}
		//add(KeyPairCombo.forConvex(convex));
	}
}
