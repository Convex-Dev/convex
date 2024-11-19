package convex.gui.components;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;

import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import convex.core.data.prim.AInteger;
import convex.core.text.Text;

/**
 * Component displaying a decimal quantity in an editable text field, constrained to a decimal with a maximum number of digits
 */
@SuppressWarnings("serial")
public class DecimalAmountField extends JTextField {
	
	protected final int decimals;

	public DecimalAmountField(int decimals) {
		super("0");
		this.decimals=decimals;
		this.setHorizontalAlignment(SwingConstants.RIGHT);
		//setColumns(10+decimals);
	}
	
	public static DecimalFormat getNumberFormat(int decimals) {
		DecimalFormat df= new DecimalFormat("#,###"+((decimals>0)?("."+Text.repeat('#',decimals)):""));
		return df;
	}
	
	@Override 
	protected Document createDefaultModel() {
		return new DecimalDocument();
	}
	
	@Override
	public Dimension getPreferredSize() {
		Dimension d=super.getPreferredSize();
		FontMetrics font=getFontMetrics(getFont());
		int pw=font.charWidth('0')*(10+decimals);
		if (d.width<pw) d.width=pw;
		return d;
	}
	
	public class DecimalDocument extends PlainDocument {
		@Override
		public void insertString(int offset, String s, AttributeSet a) throws BadLocationException {
			if (s == null) return;

			char[] newChars = s.toCharArray();
			int n=newChars.length;
			if (n==0) return;
			
			String text=super.getText(0, super.getLength());
			int tlen=text.length();
			int dotPos=text.indexOf('.');

			for (int i = 0; i < n; i++ ) {
				char c=newChars[i];
				if (Text.isASCIIDigit(c)) continue;
				if ((i==0)&&(c=='.')) continue;
				if ((c=='.')&&(dotPos<0)) {
					// found first do
					dotPos=i;
					continue;
				}
				n=i; // end of valid input
				break;
			}
			if (n==0) return;
			
			if (newChars[0]=='.') {
				if (dotPos>=0) {
					super.remove(dotPos,tlen-dotPos);
					offset=dotPos;
				} else {
					offset=tlen;
					dotPos=0;
				}
			}
			// suppress digits after decimal length
			if ((dotPos>=0)) {
				int digits=(offset+n-dotPos)-1;
				if (digits>decimals) {
					n=n-digits+decimals;
				}
			}

			// String to insert
			String insertS=new String(newChars);
			if (n<newChars.length) insertS=insertS.substring(0,n);
			
			// Everything valid, so just insert as normal
	    	super.insertString(offset, insertS, a);
			DecimalAmountField.this.setCaretPosition(offset+n);
		}
	}

	public AInteger getAmount() {
		String text=getText();
		if (text.isBlank()) return null;
		return parse(text,decimals,true);
	}
	
	public void setText(String text) {
		super.setText(text.trim());
	}

	/**
	 * Parse a string as an integer amount with the specified number of decimals
	 * @param text String to parse
	 * @param decimals Number of decimals in result quantity
	 * @param exact If true, conversion will require an exact conversion
	 * @return Integer amount, or null if not convertible
	 */
	public static AInteger parse(String text, int decimals, boolean exact) {
		try {
			text=text.trim();
			BigDecimal dec=new BigDecimal(text);
			if (decimals>0) {
				dec=dec.multiply(new BigDecimal(BigInteger.TEN.pow(decimals)));
			}
			BigInteger bi=(exact?dec.toBigIntegerExact():dec.toBigInteger());
			return AInteger.create(bi);
		} catch (NumberFormatException e) {
			return null;
		}catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
 }
