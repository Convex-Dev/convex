package convex.gui.wallet;

import java.awt.EventQueue;
import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;

import convex.api.Convex;
import convex.core.data.AccountKey;
import convex.core.cvm.Address;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.gui.components.BalanceLabel;
import convex.gui.components.Identicon;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class AccountOverview extends JPanel implements AutoCloseable {

	final BalanceLabel balance=new BalanceLabel();
	final JLabel nameLabel=new JLabel("anonymous");
	final JLabel addressLabel=new JLabel();
	Identicon identicon=new Identicon(null,Toolkit.IDENTICON_SIZE*2);
	
	private Convex convex;
	private final Thread updateThread;

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
		 
		updateThread=Thread.ofVirtual().name("account-update").start(this::updateLoop);
	}

	/** Fetches account details off the EDT: straight away, then every second while showing */
	private void updateLoop() {
		boolean first=true;
		while ((!Thread.currentThread().isInterrupted()) && convex.isConnected()) {
			try {
				if (first||isShowing()) update();
				first=false;
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Stops the update loop. Called when the owning window closes. */
	@Override
	public void close() {
		updateThread.interrupt();
	}

	/** Fetches the account's details, then shows them on the EDT */
	private void update() throws InterruptedException {
		Address a=convex.getAddress();
		// TODO: user name update
		AccountKey key=convex.getAccountKey(a);
		CVMLong bal;
		String tip;
		try {
			bal=CVMLong.create(convex.getBalance());
			tip="Convex coin balance ("+bal+" coppers)";
		} catch (ResultException e) {
			bal=null;
			tip="Convex coin balance not available: "+e.getMessage();
		}
		CVMLong shownBalance=bal;
		String shownTip=tip;
		EventQueue.invokeLater(()->{
			addressLabel.setText(RT.toString(a));
			identicon.setKey(key);
			balance.setBalance(shownBalance);
			balance.setToolTipText(shownTip);
		});
	}
}
