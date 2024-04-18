package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.util.Utils;

public abstract class ADerivedBlob extends ACountedBlob {
	
	protected ADerivedBlob(long count) {
		super(count);
	}
	
	@Override
	public int estimatedEncodingSize() {
		return getCanonical().estimatedEncodingSize();
	}

	@Override
	public final ACountedBlob slice(long start, long end) {
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
	protected abstract ACountedBlob sliceImpl(long start, long end);
	
	@Override
	public Blob toFlatBlob() {
		if (count==0) return Blob.EMPTY;
		int n=Utils.checkedInt(count);
		byte[] bs=new byte[n];
		getBytes(bs,0);
		return Blob.wrap(bs);
	}

	@Override
	protected void updateDigest(MessageDigest digest) {
		ABlob can=getCanonical();
		can.updateDigest(digest);
	}

	@Override
	public ACountedBlob append(ABlob d) {
		return ((ACountedBlob)getCanonical()).append(d);
	}

	@Override
	public boolean equals(ABlob o) {
		return o.equals(getCanonical());
	}

	@Override
	public ABlob toCanonical() {
		return toFlatBlob();
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
	public int read(long offset, long count, ByteBuffer dest) {
		ABlob can=getCanonical();
		return can.read(offset, count, dest);
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
	public ABlob toBlob() {
		return this;
	}

	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		ABlob can=getCanonical();
		return can.encode(bs, pos);
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public int getRefCount() {
		throw new UnsupportedOperationException();
	}


}
