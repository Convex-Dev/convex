package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.util.Utils;

public abstract class ADerivedBlob extends ACountedBlob {
	protected final long offset;
	
	protected ADerivedBlob(long offset,long count) {
		super(count);
		this.offset=offset;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return getCanonical().estimatedEncodingSize();
	}

	@Override
	public abstract ACountedBlob slice(long start, long end);

	@Override
	public Blob toFlatBlob() {
		int n=Utils.checkedInt(count());
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
	public int getBytes(byte[] dest, int destOffset) {
		ABlob can=getCanonical();
		return can.getBytes(dest, destOffset);
	}

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
