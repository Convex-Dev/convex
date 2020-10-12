package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * AString subclass representing a subsequence of another charsequence
 */
public class StringSlice extends AString {

	private AString source;
	private int start;

	protected StringSlice(AString source,int start, int length) {
		super(length);
		this.source=source;
		this.start=start;
	}


	public static AString create(StringTree source, int start, int len) {
		if (len==0) return Strings.EMPTY;
		if (len<0) throw new IllegalArgumentException("Negative length");
		
		int slen=source.length;
		if ((start<0)||(start+len>slen)) throw new IllegalArgumentException("Out of range");
		return new StringSlice(source,start,len);
	}
	
	@Override
	public char charAt(int index) {
		return source.charAt(index-start);
	}

	@Override
	public AString subSequence(int start, int end) {
		int len=end-start;
		if (len==0) return Strings.EMPTY;
		if (len<0) throw new IllegalArgumentException("Negative length");
		if ((start<0)||(start+len>=length)) throw new IllegalArgumentException("Out of range");
		if ((start==0)&&(len==length)) return this;
		return source.subSequence(this.start+start, this.start+end);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing?

	}

	@Override
	public int write(byte[] bs, int pos) {
		throw new UnsupportedOperationException("");
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException("");
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public int getRefCount() {
		return 0;
	}




}
