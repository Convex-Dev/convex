package convex.core.data;

import convex.core.data.impl.LongBlob;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.ErrorMessages;

/**
 * AString subclass representing a view some String data. Not canonical!
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
	public void validateCell() throws InvalidDataException {
		// Nothing?

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
	public int writeRawData(byte[] bs, int pos) {
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
	public byte byteAt(long i) {
		if ((i<0)||(i>=length)) return -1;
		return source.byteAt(i+start);
	}

	@Override
	public AString toCanonical() {
		return Strings.create(toBlob());
	}
	
	public ABlob toBlob() {
		return source.toBlob().slice(start, start+length);
	}

	@Override
	public int compareTo(ABlobLike<?> o) {
		if (o==this) return 0;
		return ((AString)getCanonical()).compareTo(o);
	}

	@Override
	public AString slice(long start, long end) {
		if (start<0) return null;
		if (end>(start+length)) return null;
		if (start>end) return null;
		return source.slice(this.start+start, this.start+end);
	}

	@Override
	protected void printEscaped(BlobBuilder sb, long start, long end) {
		long n=count();
		if ((start<0)||(start>end)||(end>n)) throw new IllegalArgumentException(ErrorMessages.badRange(start, end));
		source.printEscaped(sb, this.start+start, this.start+end);
	}

	@Override
	public boolean equals(AString b) {
		if (this==b) return true;
		if (b==null) return false;
		return false;
	}

	@Override
	public boolean equalsBytes(ABlob b) {
		if (length!=b.count()) return false;
		return toBlob().equalsBytes(b);
	}
	
	@Override
	public long longValue() {
		return slice(0,Math.min(LongBlob.LENGTH, count())).longValue();
	}

}
