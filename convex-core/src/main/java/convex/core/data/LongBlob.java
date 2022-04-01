package convex.core.data;

import java.security.MessageDigest;

import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Wrapper for an 8-byte long as a Blob
 * 
 * We use this mainly for efficient management of indexes using Longs in BlobMaps.
 * 
 */
public final class LongBlob extends ALongBlob {

	public static final int MAX_ENCODING_LENGTH = 1+1+8; // Tag plus length plus 8 bytes

	private LongBlob(long value) {
		super(value);
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
	public void getBytes(byte[] dest, int destOffset) {
		Utils.writeLong(dest, destOffset,value);
	}

	@Override
	public ABlob slice(long start, long length) {
		if ((start == 0) && (length == LENGTH)) return this;

		if (start < 0) throw new IndexOutOfBoundsException(Errors.badRange(start, length));
		return getEncoding().slice(start + 2, length);
	}

	@Override
	public Blob toFlatBlob() {
		// Trick to use cached encoding if available
		if (encoding!=null) {
			return encoding.slice(2,8);
		}
		byte[] bs=new byte[8];
		Utils.writeLong(bs, 0, value);
		return Blob.wrap(bs);
	}

	@Override
	protected void updateDigest(MessageDigest digest) {
		byte[] bs = getEncoding().getInternalArray();
		digest.update(bs, 2, (int) LENGTH);
	}

	@Override
	public long commonHexPrefixLength(ABlob b) {
		if (b == this) return LENGTH * 2;

		long max = Math.min(LENGTH, b.count());
		for (long i = 0; i < max; i++) {
			byte ai = getUnchecked(i);
			byte bi = b.getUnchecked(i);
			if (ai != bi) return (i * 2) + (Utils.firstDigitMatch(ai, bi) ? 1 : 0);
		}
		return max * 2;
	}

	@Override
	public boolean equals(ABlob a) {
		if (a instanceof LongBlob) return (((LongBlob) a).value == value);
		if (a instanceof Blob) {
			Blob b=(Blob)a;
			return ((b.count()==LENGTH)&& (b.longValue()== value));
		}
		return false;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BLOB;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		bs[pos++]=((byte) 8);
		Utils.writeLong(bs, pos, value);
		return pos+8;
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
	public long hexMatchLength(ABlob b, long start, long length) {
		if (b == this) return length;
		long end = start + length;
		for (long i = start; i < end; i++) {
			if (!(getHexDigit(i) == b.getHexDigit(i))) return i - start;
		}
		return length;
	}
	
	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

	@Override
	public boolean equalsBytes(byte[] bytes, int byteOffset) {
		return value==Utils.readLong(bytes, byteOffset);
	}
	
	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public Blob toCanonical() {
		return toFlatBlob();
	}

}
