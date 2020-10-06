package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Wrapper for an 8-byte long blob
 * 
 * We use this for efficient management of indexes using longs in BlobMaps.
 * 
 */
public class LongBlob extends ABlob {
	private final long value;

	private static final long LENGTH = 8;

	private LongBlob(long value) {
		this.value = value;
	}

	public static LongBlob create(String string) {
		byte[] bs = Utils.hexToBytes(string);
		if (bs.length != LENGTH) throw new IllegalArgumentException("Long blob requires a length 8 hex string");
		return new LongBlob(Utils.readLong(bs, 0));
	}

	public static LongBlob create(long value) {
		return new LongBlob(value);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void getBytes(byte[] dest, int destOffset) {
		Utils.writeLong(value, dest, destOffset);
	}

	@Override
	public long length() {
		return LENGTH;
	}

	@Override
	public String toHexString() {
		return Utils.toHexString(value);
	}

	@Override
	public ABlob slice(long start, long length) {
		if ((start == 0) && (length == LENGTH)) return this;

		if (start < 0) throw new IndexOutOfBoundsException(Errors.badRange(start, length));
		return getEncoding().slice(start + 2, length);
	}

	@Override
	public Blob toBlob() {
		return getEncoding().slice(2, LENGTH);
	}

	@Override
	protected void updateDigest(MessageDigest digest) {
		byte[] bs = getEncoding().getInternalArray();
		digest.update(bs, 2, (int) LENGTH);
	}

	private void checkIndex(long i) {
		if ((i < 0) || (i >= LENGTH)) throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@Override
	public byte get(long i) {
		checkIndex(i);
		return (byte) (value >> ((LENGTH - i - 1) * 8));
	}

	@Override
	public int getHexDigit(long i) {
		if ((i < 0) || (i >= LENGTH * 2)) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return 0x0F & (int) (value >> ((LENGTH * 2 - i - 1) * 4));
	}

	@Override
	public long commonHexPrefixLength(ABlob b) {
		if (b == this) return LENGTH * 2;

		long max = Math.min(LENGTH, b.length());
		for (long i = 0; i < max; i++) {
			byte ai = get(i);
			byte bi = b.get(i);
			if (ai != bi) return (i * 2) + (Utils.firstDigitMatch(ai, bi) ? 1 : 0);
		}
		return max * 2;
	}

	@Override
	public ABlob append(ABlob d) {
		return toBlob().append(d);
	}

	@Override
	public int hashCode() {
		return getHash().hashCode();
	}

	@Override
	public boolean equals(ABlob a) {
		if (a instanceof LongBlob) return (((LongBlob) a).value == value);
		return toBlob().equals(a);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = bb.put((byte) 8);
		bb = bb.putLong(value);
		return bb;
	}

	@Override
	public Blob getChunk(long i) {
		if (i == 0L) return toBlob();
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@Override
	public ByteBuffer getByteBuffer() {
		return toBlob().getByteBuffer();
	}

	@Override
	public void toHexString(StringBuilder sb) {
		sb.append(Utils.toHexString(value));
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.BLOB);
		return writeRaw(bb);
	}

	@Override
	public int estimatedEncodingSize() {
		return (int) (2 + LENGTH);
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public long hexMatch(ABlob b, long start, long length) {
		if (b == this) return length;
		long end = start + length;
		for (long i = start; i < end; i++) {
			if (!(getHexDigit(i) == b.getHexDigit(i))) return i - start;
		}
		return length;
	}

	@Override
	public long toLong() {
		return value;
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public boolean isEmbedded() {
		// Always embedded
		return true;
	}
	
	@Override
	public boolean isRegularBlob() {
		return true;
	}

}
