package convex.gui.wallet;

import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.data.AccountKey;
import convex.core.cvm.Address;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.gui.components.BalanceLabel;
import convex.gui.components.Identicon;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AccountOverview extends JPanel {

	final BalanceLabel balance=new BalanceLabel();
	final JLabel nameLabel=new JLabel("anonymous");
	final JLabel addressLabel=new JLabel();
	Identicon identicon=new Identicon(null,Toolkit.IDENTICON_SIZE*2);
	
	private Convex convex;

	public AccountOverview(Convex convex) {
		this.convex=convex;
		setLayout(new MigLayout("fill","","push[][]"));
		Font font=this.getFont();
		
		
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
		add(new JLabel("Identicon"));
		add(new JLabel("Convex Coin Balance"),"wrap");
		//add(new JLabel("Identicon"),"wrap");
		
		Font bigfont=font.deriveFont(40f);
		
		{ // Name label
			nameLabel.setFont(bigfont);
			nameLabel.setToolTipText("Convex user ID associated with this account (can be anonymous)");
			add(nameLabel);
		}
		
		{ // Address label
			addressLabel.setToolTipText("Address of this account in the global state");
			addressLabel.setFont(bigfont);
			add(addressLabel);
		}
		
		{ // Address label
			add(identicon);
		} 

		{ // Coin Balance
			balance.setFont(bigfont);
			add(balance);
		}
		//add(KeyPairCombo.forConvex(convex));
		 
		update();
		ThreadUtils.runVirtual(this::updateLoop);
	}
	
	private void updateLoop() {
		while ((!Thread.currentThread().isInterrupted()) && convex.isConnected()) {
			try {
				Thread.sleep(1000);
				if (isShowing()) {
					update();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void update() {
		try {
			Address a=convex.getAddress();
			
			// TODO: user name update
			
			String addrString=RT.toString(a);
			if (addressLabel.getText()!=addrString) {
				addressLabel.setText(addrString);
			}
			
			AccountKey key=convex.getAccountKey(a);
			identicon.setKey(key);
			
			try {
				CVMLong bal=CVMLong.create(convex.getBalance());
				if (!Utils.equals(bal,balance.getBalance())) {
					balance.setBalance(bal); 
					if (bal!=null) {
						balance.setToolTipText("Convex coin balance ("+bal+" coppers)");
					} else {
						balance.setToolTipText("Convex coin balance not available");
					}
				}
			} catch (ResultException e) {
				balance.setBalance(null);
				balance.setToolTipText("Convex coin balance not available: "+e.getMessage());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			
		}
	}
}
