package convex.gui.components;

import java.awt.Color;
import java.math.BigInteger;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;

/**
 * Component for attractive displace of coin / token balances
 */
@SuppressWarnings("serial")
public class BalanceLabel extends BaseTextPane {

	protected AInteger balance;
	
	public class BalanceMenu extends JPopupMenu {
		
		JMenuItem copyMenuItem = new JMenuItem("Copy Value",SymbolIcon.get(0xe14d));
		
		public BalanceMenu() {
			add(copyMenuItem);
			copyMenuItem.addActionListener(e->{
				Toolkit.copyToClipboard(RT.toString(balance));
			});
		}
	}

	public static final Color GOLD=new Color(255,255,0);
	public static final Color SILVER=new Color(200,200,230);
	public static final Color BRONZE=new Color(180,120,60);
	public static final Color COPPER=new Color(150,80,30);
	
	protected int decimals=9;
	
	public BalanceLabel() {
		this.setEditable(false);
	}
	
	public void setBalance(long a) {
		setBalance(CVMLong.create(a));
	}

	public void setBalance(AInteger a) {
		try {
			if (a==null) {
				setText("<No balance>");
				balance=null;
				return;
			} else {
				balance=a;
			}
			
			int size=getFont().getSize();
			
			BigInteger unit=getUnit(decimals);
			BigInteger bi=a.big();
			BigInteger change=bi.remainder(unit);
			BigInteger coins=bi.divide(unit);
	
			setText("");
			String cs=Text.toFriendlyNumber(coins.longValue());
			append(cs,GOLD,size);
			append(".",SILVER,size);
			String ch=Text.zeroPad(change,decimals);
			for (int i=0; i<decimals; i+=3) {
				Color c=changeColour(i);
				String chs=ch.substring(i,Math.min(decimals,i+3));
				append(chs,c,size*2/3);
			}
			
			Toolkit.addPopupMenu(this, new BalanceMenu());
		} catch (Throwable e) {
			e.printStackTrace();
			setText(e.getMessage());
			balance=null;
		}
		
	}

	private static Color[] CCOLS=new Color[] {SILVER, BRONZE, COPPER};
	
	private static Color changeColour(int i) {
		return CCOLS[(i/3)%3];
	}

	private static BigInteger getUnit(int decimals) {
		return BigInteger.TEN.pow(decimals);
	}

	public void setBalance(Convex convex) {
		try {
			Long bal=convex.getBalance();
			if (bal!=null) {
				setBalance(bal);
			}
		} catch (Exception e) {
			setText("<Can't get balance>");
		}
	}
	
	@Override
	public void setText(String s) {
		super.setText("");
		append(s,Color.ORANGE,getFont().getSize());
	}

	public void setBalance(Result r) {
		ACell bal=r.getValue();
		ACell error=r.getErrorCode();
		if (error!=null) {
			if (ErrorCodes.NOBODY.equals(error)) {
				setText("<no account>");
			} else {
				setText(error.toString());
			}
			return;
		}
		
		if (bal instanceof AInteger) {
			setBalance((AInteger)bal);
		} else {
			setText("<bad value>");
		}
	}
}
