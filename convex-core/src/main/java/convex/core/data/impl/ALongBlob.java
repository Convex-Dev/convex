package convex.core.data.impl;

import java.nio.ByteBuffer;

import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.RefDirect;
import convex.core.data.util.BlobBuilder;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

public abstract class ALongBlob extends ABlob {

	public static final int LENGTH = 8;
	protected static final int HEX_LENGTH = LENGTH*2;
	
	protected final long value;

	protected ALongBlob(long value) {
		super(LENGTH);
		this.value=value;
	}
	
	@Override
	public final long hexLength() {
		return HEX_LENGTH;
	}
	
	@Override
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		// Create Ref at maximum status to reflect internal embedded nature
		Ref<ACell> newRef= RefDirect.create(this,cachedHash(),Ref.KNOWN_EMBEDDED_MASK);
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}

	@Override
	public boolean appendHex(BlobBuilder bb,long length) {
		long n=Math.min(length, HEX_LENGTH);
		for (int i=0; i<n; i++) {
			bb.append(Utils.toHexChar(getHexDigit(i)));
		}
		return n==HEX_LENGTH;
	}
	
	@Override
	public int getHexDigit(long i) {
		assert ((i >= 0) && (i < HEX_LENGTH)) : "Bad Hex Digit position in LongBlob";
		return 0x0F & (int) (value >> ((HEX_LENGTH - i - 1) * 4));
	}

	@Override
	public abstract ABlob slice(long start, long end);

	@Override
	public abstract Blob toFlatBlob();
	
	private static void checkIndex(long i) {
		if ((i < 0) || (i >= LENGTH)) throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
	}

	@Override
	public final byte byteAt(long i) {
		checkIndex(i);
		return (byte) Utils.longByteAt(value,i);
	}
	
	@Override
	public final byte byteAtUnchecked(long i) {
		return (byte) Utils.longByteAt(value,i);
	}

	@Override
	public final ABlob append(ABlob d) {
		return toFlatBlob().append(d);
	}

	@Override
	public abstract boolean equals(ABlob o);
	
	@Override
	public final int getBytes(byte[] bs, int pos) {
		pos=Utils.writeLong(bs, pos, value);
		return pos;
	}

	@Override
	public final Blob getChunk(long i) {
		if (i == 0L) return toFlatBlob();
		throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
	}

	@Override
	public final ByteBuffer getByteBuffer() {
		return toFlatBlob().getByteBuffer();
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
	public final long longValue() {
		return value;
	}
	
	@Override
	public int compareTo(ABlobLike<?> b) {
		if (b.count()==LENGTH) {
			return compareTo(b.longValue());
		} else {
			return -b.compareTo(this);
		}
	}

	protected int compareTo(long bvalue) {
		return Long.compareUnsigned(value, bvalue);
	}

	@Override
	public final boolean equalsBytes(ABlob b) {
		if (b.count()!=LENGTH) return false;
		return value==b.longValue();
	}
	
	@Override
	public boolean equalsBytes(byte[] bytes, long byteOffset) {
		return value==Utils.readLong(bytes, Utils.checkedInt(byteOffset),8);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public ABlob toCanonical() {
		return this;
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
	public int getRefCount() {
		// No Refs
		return 0;
	}

	@Override
	public final boolean isEmbedded() {
		// Always embedded
		return true;
	}

	@Override
	protected final long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	@Override
	public boolean isChunkPacked() {
		// Never a full chunk
		return false;
	}

	@Override
	public boolean isFullyPacked() {
		// Never a full packed blob
		return false;
	}
	
	@Override
	public int toByteBuffer(long offset, long count, ByteBuffer dest) {
		if ((offset==0)&&(count==LENGTH)) {
			dest.putLong(longValue());
			return LENGTH;
		}
		return toFlatBlob().toByteBuffer(offset,count,dest);
	}


}
