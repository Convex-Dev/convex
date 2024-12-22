package convex.core.data;

import convex.core.data.impl.LongBlob;
import convex.core.data.prim.CVMLong;
import convex.core.util.Bits;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Abstract base class for 8-byte bloblike extension Values such as Address and CAD Extension Values 0xE0 - 0xEF
 */
public abstract class AExtensionValue extends ABlobLike<CVMLong> {

	/**
	 * Length of an extension value in bytes (when considered as a Blob)
	 */
	protected static final int BYTE_LENGTH = 8;
	
	protected final long value;

	protected AExtensionValue(long value) {
		this.value=value;
	}
	
	@Override
	public final long count() {
		return BYTE_LENGTH;
	}
	
	@Override
	public int estimatedEncodingSize() {
		// tag VLC bytes
		return 1 + Format.MAX_VLQ_COUNT_LENGTH;
	}

	@Override
	public Blob slice(long start, long end) {
		return toFlatBlob().slice(start,end);
	}
	
	@Override
	public ABlob toBlob() {
		return LongBlob.create(longValue());
	}
	
	@Override
	public final long longValue() {
		return value;
	}
	
	protected static void checkIndex(long i) {
		if ((i < 0) || (i >= BYTE_LENGTH)) throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
	}
	
	@Override
	public Blob toFlatBlob() {
		byte[] bs=new byte[BYTE_LENGTH];
		getBytes(bs,0);
		return Blob.wrap(bs);
	}
	
	@Override
	protected ACell toCanonical() {
		// Always canonical
		return this;
	}
	
	@Override
	public long hexMatch(ABlobLike<?> b, long start, long length) {
		for (int i=0; i<length; i++) {
			int c=b.getHexDigit(start+i);
			if (c!=getHexDigit(start+i)) return i;
		}	
		return length;
	}
	
	@Override
	public boolean equalsBytes(ABlob b) {
		if (b.count()!=BYTE_LENGTH) return false;
		return b.longValue()==longValue();
	}
	
	protected int compareTo(long bvalue) {
		return Long.compareUnsigned(longValue(), bvalue);
	}
	
	@Override
	public int compareTo(ABlobLike<?> b) {
		if (b.count()==BYTE_LENGTH) {
			return compareTo(b.longValue());
		} else {
			// safe because must be a different type
			return -b.compareTo(this);
		}
	}
	
	@Override
	public AExtensionValue empty() {
		// There is no empty extension value
		return null;
	}
	
	@Override
	public final int hashCode() {
		return Bits.hash32(value);
	}

	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override
	protected final long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	@Override
	public boolean equals(ACell a) {
		if (a instanceof AExtensionValue) return equals((AExtensionValue) a);
		return Cells.equalsGeneric(this, a);
	}
	
	protected boolean equals(AExtensionValue a) {
		if (getTag()!=a.getTag()) return false;
		return longValue()==a.longValue();
	}
	
	@Override
	public CVMLong get(long i) {
		checkIndex(i);
		return CVMLong.create(Utils.longByteAt(longValue(),i));
	}

	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		return this;
	}
	
	@Override
	public final int getRefCount() {
		// No Refs
		return 0;
	}
}
