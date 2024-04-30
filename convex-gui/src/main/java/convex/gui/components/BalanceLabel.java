package convex.gui.components;

import java.awt.Color;
import java.math.BigInteger;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.text.Text;

@SuppressWarnings("serial")
public class BalanceLabel extends BaseTextPane {

	protected int decimals=9;
	
	public BalanceLabel() {
		
	}
	
	public void setBalance(long a) {
		setBalance(CVMLong.create(a));
	}

	public void setBalance(AInteger a) {
		try {
			if (a==null) {
				setText("<No balance>");
				return;
			}
			
			BigInteger unit=getUnit(decimals);
			BigInteger bi=a.big();
			BigInteger change=bi.remainder(unit);
			BigInteger coins=bi.divide(unit);
	
			setText("");
			String cs=Text.toFriendlyNumber(coins.longValue());
			append(cs,Color.YELLOW);
			append(".");
			String ch=Text.zeroPad(change,decimals);
			for (int i=0; i<decimals; i+=3) {
				Color c=changeColour(i);
				String chs=ch.substring(i,Math.min(decimals,i+3));
				append(chs,c,getFont().getSize()*2/3);
			}
		} catch (Throwable e) {
			e.printStackTrace();
			setText(e.getMessage());
		}
		
	}

	private static Color[] CCOLS=new Color[] {new Color(200,200,230), new Color(180,120,60), new Color(150,80,30)};
	
	private static Color changeColour(int i) {
		return CCOLS[(i/3)%3];
	}

	private static BigInteger getUnit(int decimals) {
		return BigInteger.TEN.pow(decimals);
	}
}
