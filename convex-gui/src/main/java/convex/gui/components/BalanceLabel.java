package convex.gui.components;

import java.awt.Color;
import java.math.BigInteger;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

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
		
		JMenuItem copyMenuItem = new JMenuItem("Copy Value",SymbolIcon.get(0xe14d,Toolkit.SMALL_ICON_SIZE));
		
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
	private Color balanceColour=GOLD;
	
	public BalanceLabel() {
		this.setEditable(false);
		
		AttributeSet attribs = new SimpleAttributeSet();
		attribs=styleContext.addAttribute(attribs, StyleConstants.Alignment, StyleConstants.ALIGN_RIGHT);
		attribs=styleContext.addAttribute(attribs, StyleConstants.FontFamily, getFont().getFamily());
		//StyleConstants.setAlignment(attribs, StyleConstants.ALIGN_RIGHT);
		//StyleConstants.setFontFamily(attribs, Font.PLAIN);
		this.setParagraphAttributes(attribs, true);
	}
	
	public void setBalance(long a) {
		setBalance(CVMLong.create(a));
	}
	
	public void setDecimals(int decimals) {
		this.decimals=decimals;
	}
	
	public void setBalanceColour(Color c) {
		this.balanceColour=c;
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
			append(cs,balanceColour,size);
			append(".",SILVER,size);
			String ch=Text.zeroPad(change,decimals);
			int decimalSize=size*2/3;
			for (int i=0; i<decimals; i+=3) {
				Color c=changeColour(i);
				String chs=ch.substring(i,Math.min(decimals,i+3));
				append(chs,c,decimalSize);
			}
			for (int i=decimals; i<9; i++) {
				append(" ",balanceColour,decimalSize);
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
