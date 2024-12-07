package convex.gui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.math.BigInteger;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.core.util.Utils;
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
	public static final Color BRONZE=new Color(200,120,60);
	public static final Color COPPER=new Color(150,100,30);
	
	protected int decimals=9;
	private Color balanceColour=GOLD;
	
	public BalanceLabel() {
		this.setEditable(false);
		this.setFocusable(false);
	}
	
	public void setBalance(long a) {
		setBalance(CVMLong.create(a));
	}
	
	public void setDecimals(int decimals) {
		this.decimals=decimals;
		// reset balance to ensure correct display
		refreshBalance();
	}
	
	private synchronized void refreshBalance() {
		try {	
			if (balance==null) {
				super.setText("-         ");
				return;			
			} else {
				// Clear balance for appends
				super.setText("");
			}
			int size=getFont().getSize();
			
			BigInteger unit=getUnit(decimals);
			BigInteger bi=balance.big();
			BigInteger change=bi.remainder(unit);
			BigInteger coins=bi.divide(unit);
	
			AttributeSet attribs = new SimpleAttributeSet();
			attribs=styleContext.addAttribute(attribs, StyleConstants.Alignment, StyleConstants.ALIGN_RIGHT);
			attribs=styleContext.addAttribute(attribs, StyleConstants.FontFamily, getFont().getFamily());
			//StyleConstants.setAlignment(attribs, StyleConstants.ALIGN_RIGHT);
			//StyleConstants.setFontFamily(attribs, Font.PLAIN);
			this.setParagraphAttributes(attribs, true);

			
			String cs=Text.toFriendlyNumber(coins.longValue());
			append(cs,balanceColour,size);
			String ch=Text.zeroPad(change,decimals);
			int decimalSize=size*2/3;
			append(decimals>0?".":" ",SILVER,decimalSize);
			for (int i=0; i<decimals; i+=3) {
				Color c=changeColour(i);
				String chs=ch.substring(i,Math.min(decimals,i+3));
				append(chs,c,decimalSize);
			}
			for (int i=decimals; i<9; i++) {
				// Should be a unicode "tabular space" i.e. the width of a regular digit
				append("\u2007",balanceColour,decimalSize);
			}
			
			Toolkit.addPopupMenu(this, new BalanceMenu());
		} catch (NullPointerException e) {
			e.printStackTrace();
			super.setText(e.getMessage());
			balance=null;
		}	
	}

	public void setBalanceColour(Color c) {
		this.balanceColour=c;
	}
	
	@Override
	public Dimension getPreferredSize() {
		Dimension d=super.getPreferredSize();
		FontMetrics font=getFontMetrics(getFont());
		int pw=font.charWidth('0')*(10+decimals);
		if (d.width<pw) d.width=pw;
		return d;
	}

	public void setBalance(AInteger a) {
		if (Utils.equals(a, balance)) return;
		balance=a;
		refreshBalance();
	}
	
	public AInteger getBalance() {
		return balance;
	}

	private static Color[] CCOLS=new Color[] {SILVER, BRONZE, COPPER};
	
	private static Color changeColour(int i) {
		return CCOLS[(i/3)%3];
	}

	private static BigInteger getUnit(int decimals) {
		return BigInteger.TEN.pow(decimals);
	}
	
	@Override
	public void setText(String s) {
		AInteger val=DecimalAmountField.parse(s,decimals,false);
		if (val==null) {
			super.setText("");
		} else {
			setBalance(val);
		}
	}

	public void setFromResult(Result r) {
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
