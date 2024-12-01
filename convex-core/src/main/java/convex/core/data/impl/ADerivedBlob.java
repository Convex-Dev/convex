package convex.core.data.impl;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.Blob;
import convex.core.util.Utils;

/**
 * Abstract Blob base base for Blobs that derive their functionality from other sources.
 * 
 * Allows extension of ABlob interface to various backing stores.
 */
public abstract class ADerivedBlob extends ABlob {
	
	protected ADerivedBlob(long count) {
		super(count);
	}
	
	@Override
	public int estimatedEncodingSize() {
		return getCanonical().estimatedEncodingSize();
	}

	@Override
	public final ABlob slice(long start, long end) {
		if (start < 0) return null;
		if (end > this.count) return null;
		long length=end-start;
		if (length<0) return null;
		if (length==0) return empty();
		if (length==count) return this;
		return sliceImpl(start,end);
	}

	/**
	 * Constructs a slice of this Blob as the same type. Assumes a new slice must be constructed
	 * and that bounds have already been checked. 
	 * @param start Start of slice
	 * @param end End of slice
	 * @return New slice instance
	 */
	protected abstract ABlob sliceImpl(long start, long end);
	
	@Override
	public Blob toFlatBlob() {
		if (count==0) return Blob.EMPTY;
		int n=Utils.checkedInt(count);
		byte[] bs=new byte[n];
		getBytes(bs,0);
		return Blob.wrap(bs);
	}

	@Override
	public void updateDigest(MessageDigest digest) {
		ABlob can=getCanonical();
		can.updateDigest(digest);
	}

	@Override
	public ABlob append(ABlob d) {
		return ((ABlob)getCanonical()).append(d);
	}

	@Override
	public boolean equals(ABlob o) {
		return o.equals(getCanonical());
	}

	@Override
	public ABlob toCanonical() {
		// Not great, but probably best we can do in general?
		return toFlatBlob().getCanonical();
	}

	@Override
	public boolean equalsBytes(byte[] bytes, long offset) {
		ABlob can=getCanonical();
		return can.equalsBytes(bytes,offset);
	}

	@Override
	public Blob getChunk(long i) {
		return ((ABlob)getCanonical()).getChunk(i);
	}

	@Override
	public ByteBuffer getByteBuffer() {
		ABlob can=getCanonical();
		return can.getByteBuffer();
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		ABlob can=getCanonical();
		return can.encodeRaw(bs,pos);
	}

	@Override
	public boolean equalsBytes(ABlob b) {
		ABlob can=getCanonical();
		return can.equalsBytes(b);
	}

	@Override
	public boolean isFullyPacked() {
		ABlob can=getCanonical();
		return can.isFullyPacked();
	}

	@Override
	public int toByteBuffer(long offset, long count, ByteBuffer dest) {
		ABlob can=getCanonical();
		return can.toByteBuffer(offset, count, dest);
	}

	@Override
	public long hexMatch(ABlobLike<?> b, long start, long length) {
		ABlob can=getCanonical();
		return can.hexMatch(b, start, length);
	}

	@Override
	public abstract int getBytes(byte[] dest, int destOffset);

	@Override
	public long longValue() {
		ABlob can=getCanonical();
		return can.longValue();
	}

	@Override
	public boolean isCanonical() {
		// We probably aren't canonical if we are a derived Blob, though it is possible
		return false;
	}

	@Override
	public int getRefCount() {
		throw new UnsupportedOperationException();
	}


}
