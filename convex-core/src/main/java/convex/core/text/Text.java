package convex.core.text;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;

import convex.core.Coin;
import convex.core.data.prim.CVMDouble;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

public class Text {
	private static final int WHITESPACE_LENGTH = 32;
	private static final String ZEROS_9 = "000000000";
	private static String WHITESPACE_32 = "                                "; // 32 spaces
	private static DecimalFormatSymbols symbols;

	static {
		symbols = new DecimalFormatSymbols();
		symbols.setDecimalSeparator('.');
		symbols.setGroupingSeparator(',');
	}

	/**
	 * Return true if the character is an ASCII numeric digit
	 * @param c Character to test
	 * @return True if ASCII digit, false otherwise
	 */
	public static boolean isASCIIDigit(char c) {
		return (c>='0')&&(c<='9');
	}
	
	public static String whiteSpace(int length) {
		if (length < 0) throw new IllegalArgumentException("Negative whitespace requested!");
		if (length == 0) return "";

		if (length <= WHITESPACE_LENGTH) {
			return WHITESPACE_32.substring(0, length);
		}

		StringBuilder sb = new StringBuilder(length);
		for (int i = WHITESPACE_LENGTH; i <= length; i += WHITESPACE_LENGTH) {
			sb.append(WHITESPACE_32);
		}
		sb.append(whiteSpace(length & 0x1F));
		return sb.toString();
	}

	public static String leftPad(String s, int length) {
		if (s == null) s = "";
		int spaces = length - s.length();
		if (spaces < 0) {
			return s.substring(0,length);
			// throw new IllegalArgumentException("String [" + s + "] too long for pad length: " + length);
		}
		return whiteSpace(spaces) + s;
	}

	public static String leftPad(long value, int length) {
		return leftPad(Long.toString(value), length);
	}

	public static String rightPad(String s, int length) {
		if (s == null) s = "";
		int spaces = length - s.length();
		if (spaces < 0) throw new IllegalArgumentException("String [" + s + "] too long for pad length: " + length);
		return s + whiteSpace(spaces);
	}

	public static String rightPad(long value, int length) {
		return rightPad(Long.toString(value), length);
	}

	static DecimalFormat balanceFormatter = new DecimalFormat("#,###", symbols);

	public static String toFriendlyNumber(long value) {
		return balanceFormatter.format(value);
	}
	
	static DecimalFormat percentFormatter = new DecimalFormat("##.###%", symbols);
	public static String toPercentString(double value) {
		return percentFormatter.format(value);
	}
	
	static DecimalFormat decimalFormatter = new DecimalFormat("#,##0.####", symbols);
	public static String toFriendlyDecimal(double value) {
		if (!Double.isFinite(value)) return CVMDouble.create(value).toString();
		return decimalFormatter.format(value);
	}

	public static String toFriendlyIntString(double value) {
		return toFriendlyNumber((long) value);
	}
	

	/**
	 * Format function for Convex Coin balances
	 * @param balance Balance in Convex Coins
	 * @return Formatted String like "12.000567123"
	 */
	public static String toFriendlyBalance(long balance) {
		return toFriendlyBalance(balance,Coin.DECIMALS);
	}
	
	/**
	 * Format function for Convex Coin balances
	 * @param balance Balance in Convex Coins
	 * @return Formatted String like "12.000567123"
	 */
	public static String toFriendlyBalance(long balance, int decimals) {
		if (!Coin.isValidAmount(balance)) throw new IllegalArgumentException("Invalid balance)");
		long gold=balance/Coin.GOLD;
		long copper = balance%Coin.GOLD;
		String goldString=toFriendlyNumber(gold);
		if (decimals<=0) return goldString;
		
		String copperString=Long.toString(copper);
		int cn=copperString.length();
		if (cn<Coin.DECIMALS) {
			copperString=ZEROS_9.substring(cn,Coin.DECIMALS)+copperString;
		}
		copperString=copperString.substring(0,decimals);
		return goldString+"."+copperString;
	}


	static final DateTimeFormatter formatter = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

	public static String dateFormat(long timestamp) {
		return formatter.format(Instant.ofEpochMilli(timestamp));
	}

	/**
	 * Writes a UTF-8 byte, escaped as necessary for Java. This works because only single byte ASCII
	 * characters need escaping.
	 * 
	 * @param sb BlobBuilder to append UTF-8 bytes
	 * @param b Byte to write
	 */
	public static void writeEscapedByte(BlobBuilder sb, byte b) {
		char c=(char) b;
		switch (c) {
		    case '"': sb.append('\\'); sb.append('"'); return;
		    case '\\': sb.append('\\'); sb.append('\\'); return;
		    // case '\'': sb.append('\\'); sb.append('\''); return; See #407
		    case '\t': sb.append('\\'); sb.append('t'); return;
		    case '\b': sb.append('\\'); sb.append('b'); return;
		    case '\n': sb.append('\\'); sb.append('n'); return;
		    case '\r': sb.append('\\'); sb.append('r'); return;
		    case '\f': sb.append('\\'); sb.append('f'); return;
			default: sb.append(b);
		}
	}

	public static int lineCount(String text) {
		if (text==null) return 0;
		
		int n=1;
		for (int i=0; i<text.length(); i++) {
			if (text.charAt(i)=='\n') n++;
		}
		return n;
	}

	public static int columnCount(String text) {
		if (text==null) return 0;
		
		int result=0;
		int n=0;
		for (int i=0; i<text.length(); i++) {
			if (text.charAt(i)=='\n') {
				n=0;
			} else {
				n++;
				if (n>result) result=n;
			}
		}
		return result;
	}

	/**
	 * Zero pads a positive integer out to the specified number of digits
	 * @param b Integer to pad
	 * @param digits Number of digits desired
	 * @return String representation of Integer
	 */
	public static String zeroPad(BigInteger b, int digits) {
		if (digits>9) throw new IllegalArgumentException("Too many digits!!");
		if (b.signum()<0) throw new IllegalArgumentException("Negative number!");
		String s=b.toString();
		int n=s.length();
		if (n<digits) {
			s=ZEROS_9.substring(0,digits-n)+s;
		}
		return s;
	}

	public static String repeat(char c, int count) {
		char[] cs=new char[count];
		Arrays.fill(cs,c);
		return new String(cs);
	}
	
	public static String escapeJava(String s) {
		throw new TODOException();
	}

	/**
	 * Unescapes a Java string
	 * @param st String to unescape
	 * @return Unescaped string, or null if not a valid Java String
	 */
	public static String unescapeJava(String st) {
	    StringBuilder sb = null;
	    int n=st.length();

	    for (int i = 0; i < n; i++) {
	    	int startPos=i;
	        char ch = st.charAt(i);
	        if ((ch == '\\')&&(i+1<n)) {
	            char nextChar = st.charAt(i + 1);
	            
	            // Check for octal escape, consumes 1-3 octal chars greedily
	            if (nextChar >= '0' && nextChar <= '7') {
	                int code=Utils.octalVal(nextChar);
	                for (int j=i+2; j<i+4; j++) {
	                	if (j>=n) break;
	                	int v=Utils.octalVal(st.charAt(j));
	                	if (v<0) break; // no more octal
	                	if (code>=32) break; // wouldn't be valid, JLS maximum octal value of 377
	                	code=code*8+v;
	                	i++;
	                }
	                ch=(char) code;
	            } else {
		            switch (nextChar) {
		            case '\\':
		                ch = '\\';
		                break;
		            case 'b':
		                ch = '\b';
		                break;
		            case 'f':
		                ch = '\f';
		                break;
		            case 'n':
		                ch = '\n';
		                break;
		            case 'r':
		                ch = '\r';
		                break;
		            case 't':
		                ch = '\t';
		                break;
		            case '\"':
		                ch = '\"';
		                break;
		            case '\'':
		                ch = '\'';
		                break;
		            // Hex Unicode: u????
		            case 'u':
		                if (i+6 > n) {
		                	return null; // insufficient chars for unicode
		                }
		                int cp=0;
		                for (int j=0; j<4; j++) {
		                	int v=Utils.hexVal(st.charAt(i+j+2));
		                	if (v<0) { // not a hex value
		                		return null;
		                	}
		                	cp=cp*16+Utils.hexVal(st.charAt(i+j+2));
		                }
		    	        if (sb==null) sb=new StringBuilder(st.substring(0, startPos));
		                sb.append(Character.toChars(cp));
		                i += 5; // skip extra 5 chars on top of loop increment
		                continue;
		            }
	            }
	            i++; // skip a char, since we consumed nextChar
	        }
	        // We are appending a single char
	        if (sb==null) sb=new StringBuilder(st.substring(0, startPos));
	        sb.append(ch);
 	    }
	    return (sb==null)?st:sb.toString();
	}

}
