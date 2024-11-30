package convex.core.text;

import java.text.FieldPosition;
import java.text.ParsePosition;

import convex.core.cvm.Address;

@SuppressWarnings("serial")
public class AddressFormat extends AFormat {

	public static final AddressFormat INSTANCE = new AddressFormat();

	@Override
	public StringBuffer format(Object obj, StringBuffer sb, FieldPosition pos) {
		if (obj instanceof Address) {
			Address a = (Address)obj;
			sb.append(a.toString());
		}
		return sb;
	}

	@Override
	public Object parseObject(String source, ParsePosition pos) {
		int ix=pos.getIndex();
		int n=source.length();
		if (ix>=n) {
			pos.setErrorIndex(ix);
			return null;
		}
		while (Character.isWhitespace(source.charAt(ix))) {
			ix++;
			if (ix>=n) {
				pos.setErrorIndex(ix);
				return null;
			}
		}
		if (source.charAt(ix)=='#') ix++;
		if (ix>=n) {
			pos.setErrorIndex(ix);
			return null;
		}
		
		long v=0;
		int i=ix;
		for (; i<n; i++) {
			char d=source.charAt(i);
			if (Text.isASCIIDigit(d)) {
				if (v>(Long.MAX_VALUE/10)) {
					pos.setErrorIndex(i);
					return null;
				}
				
				v=v*10+(int)(d-'0');
			} else {
				// fail with no digits
				if (i==ix) {
					pos.setErrorIndex(i);
					return null;
				}
				break;
			}
		}
		pos.setIndex(i);
		return Address.create(v);
	}

}
