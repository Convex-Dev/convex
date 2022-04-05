package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.InvalidDataException;

/**
 * AString subclass representing a subsequence of some Blob data
 */
public class StringSlice extends AString {

	private AString source;
	private long start;

	protected StringSlice(AString source,long start, long length) {
		super(length);
		this.source=source;
		this.start=start;
	}

	public static AString create(StringTree source, long start, long len) {
		if (len==0) return StringShort.EMPTY;
		if (len<0) throw new IllegalArgumentException("Negative length");
		
		long slen=source.length;
		if ((start<0)||(start+len>slen)) throw new IllegalArgumentException("Out of range");
		return new StringSlice(source,start,len);
	}

	@Override
	public AString subString(long start, long end) {
		long len=end-start;
		if (len==0) return StringShort.EMPTY;
		if (len<0) throw new IllegalArgumentException("Negative length");
		if ((start<0)||(start+len>=length)) throw new IllegalArgumentException("Out of range");
		if ((start==0)&&(len==length)) return this;
		return source.subString(this.start+start, this.start+end);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing?

	}
	
	@Override
	public StringShort empty() {
		return StringShort.EMPTY;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		throw new UnsupportedOperationException("Can't encode StringSlice");
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException("Can't encode StringSlice");
	}
	
	@Override
	public int encodeRawData(byte[] bs, int pos) {
		throw new UnsupportedOperationException("Can't encode StringSlice");
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public boolean isCanonical() {
		return false;
	}
	
	@Override public final boolean isCVMValue() {
		return false;
	}

	@Override
	public int getRefCount() {
		return 0;
	}
	
	@Override
	protected byte byteAt(long i) {
		if ((i<0)||(i>=length)) return -1;
		return source.byteAt(i+start);
	}

	@Override
	public AString toCanonical() {
		return Strings.create(toBlob());
	}
	
	public ABlob toBlob() {
		return source.toBlob().slice(start, length);
	}

	@Override
	public int compareTo(AString o) {
		return toCanonical().compareTo(o);
	}

	@Override
	protected void writeToBuffer(ByteBuffer bb) {
		source.writeToBuffer(bb);
	}


}
