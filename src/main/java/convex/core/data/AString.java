package convex.core.data;

/**
 * Class representing a CVM String
 */
public abstract class AString extends ACell implements CharSequence {

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
		sb.append(this);
	}

	@Override
	public int length() {
		return length;
	}
	
	@Override 
	public String toString() {
		StringBuilder sb=new StringBuilder(length); 
		print(sb);
		return sb.toString();
	}


}
