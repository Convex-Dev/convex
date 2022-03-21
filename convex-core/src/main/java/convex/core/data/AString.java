package convex.core.data;

import convex.core.data.prim.CVMChar;
import convex.core.data.type.AType;
import convex.core.data.type.Types;

/**
 * Class representing a CVM String
 */
public abstract class AString extends ACountable<CVMChar> implements CharSequence, Comparable<AString> {

	
	protected int length;
	
	protected AString(int length) {
		this.length=length;
	}
	
	@Override
	public AType getType() {
		return Types.STRING;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append('"');
		// TODO. Fix escaping.
		sb.append(this);
		sb.append('"');
	}

	@Override
	public int length() {
		return length;
	}
	
	@Override
	public long count() {
		return length;
	}
	
	public StringShort empty() {
		return Strings.EMPTY;
	}

	protected abstract AString append(char charValue);

	@Override
	public CVMChar get(long i) {
		return CVMChar.create(charAt((int)i));
	}
	
	@Override
	public Ref<CVMChar> getElementRef(long i) {
		return get(i).getRef();
	}
	
	@Override
	public int compareTo(AString o) {
		return CharSequence.compare(this,o);
	}
	
	@Override 
	public String toString() {
		StringBuilder sb=new StringBuilder(length); 
		appendToStringBuffer(sb,0,length());
		return sb.toString();
	}
	
	protected abstract void appendToStringBuffer(StringBuilder sb, int start, int length);

	@Override
	public abstract AString subSequence(int start, int end);

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.STRING;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public final byte getTag() {
		return Tag.STRING;
	}
}
