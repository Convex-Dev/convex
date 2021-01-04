package convex.core.data;

/**
 * Class representing a CVM String
 */
public abstract class AString extends ACell implements CharSequence, Comparable<AString> {

	protected int length;
	
	protected AString(int length) {
		this.length=length;
	}
	
	@Override
	public void ednString(StringBuilder sb) {
		sb.append('"');
		// TODO: fix quoting
		sb.append(this);
		sb.append('"');
	}

	@Override
	public void print(StringBuilder sb) {
		ednString(sb);
	}

	@Override
	public int length() {
		return length;
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
}
