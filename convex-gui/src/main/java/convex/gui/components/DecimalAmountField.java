package convex.gui.components;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;

import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import org.bouncycastle.util.Arrays;

import convex.core.data.prim.AInteger;
import convex.core.text.Text;

@SuppressWarnings("serial")
public class DecimalAmountField extends JTextField {
	
	protected final int decimals;

	public DecimalAmountField(int decimals) {
		super("0");
		this.decimals=decimals;
		this.setHorizontalAlignment(SwingConstants.RIGHT);
		setColumns(20);
	}
	
	public static DecimalFormat getNumberFormat(int decimals) {
		DecimalFormat df= new DecimalFormat("#,###"+((decimals>0)?("."+Text.repeat('#',decimals)):""));
		return df;
	}
	
	@Override 
	protected Document createDefaultModel() {
		return new DecimalDocument();
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
				return; // not valid so exit function early
			}
			
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
					int newN=n-digits+decimals;
					newChars=Arrays.copyOfRange(newChars, 0, newN);
					n=newN;
				}
			}

			// Everything valid, so just insert as normal
	    	super.insertString(offset, new String(newChars), a);
			DecimalAmountField.this.setCaretPosition(offset+n);
		}
	}

	public AInteger getAmount() {
		String text=getText();
		if (text.isBlank()) return null;
		return parse(text,decimals,true);
	}
	
	public void setText(String text) {
		AInteger amt=DecimalAmountField.parse(text,decimals,false);
		if (amt!=null) {
			super.setText(text.trim());
		} else {
			// no change
		}
	}

	static AInteger parse(String text, int decimals, boolean exact) {
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
