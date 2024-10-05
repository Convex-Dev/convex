package convex.core.data.impl;

import java.security.MessageDigest;

import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.Blob;
import convex.core.util.Utils;

/**
 * Wrapper for an 8-byte long as a Blob
 * 
 * We use this mainly for efficient management of indexes using Longs in Indexes.
 * 
 */
public final class LongBlob extends ALongBlob {

	private static final int HEADER_LENGTH=2; // tag plus length byte
	public static final int MAX_ENCODING_LENGTH = HEADER_LENGTH+8; // Tag plus length plus 8 bytes
	
	public static final LongBlob ZERO = create(0);

	private LongBlob(long value) {
		super(value);
	}

	public static LongBlob create(String string) {
		byte[] bs = Utils.hexToBytes(string);
		int bl=bs.length;
		return new LongBlob(Utils.readLong(bs, Math.max(0, bl-8),Math.min(8, bl)));
	}

	public static LongBlob create(long value) {
		return new LongBlob(value);
	}
	
	@Override
	public ABlob slice(long start, long end) {
		if ((start == 0) && (end == LENGTH)) return toFlatBlob();

		if (start < 0) return null;
		return getEncoding().slice(start + 2, end+2);
	}

	
	@Override
	public void updateDigest(MessageDigest digest) {
		Blob b=getEncoding();
		byte[] bs = b.getInternalArray();
		digest.update(bs, b.getInternalOffset()+2, (int) LENGTH);
	}

	@Override
	public long hexMatch(ABlobLike<?> b) {
		if (b == this) return LENGTH * 2;

		long max = Math.min(LENGTH, b.count());
		for (long i = 0; i < max; i++) {
			byte ai = byteAtUnchecked(i);
			byte bi = b.byteAtUnchecked(i);
			if (ai != bi) return (i * 2) + (Utils.firstDigitMatch(ai, bi) ? 1 : 0);
		}
		return max * 2;
	}

	@Override
	public boolean equals(ABlob a) {
		if (a instanceof LongBlob) return (((LongBlob) a).value == value);
		return ((a.count()==LENGTH)&& (a.longValue()== value));
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		bs[pos++]=((byte) 8);
		Utils.writeLong(bs, pos, value);
		return pos+8;
	}

	@Override
	public int estimatedEncodingSize() {
		return HEADER_LENGTH + LENGTH;
	}

	@Override
	public long hexMatch(ABlobLike<?> b, long start, long length) {
		if (b == this) return length;
		long end = start + length;
		for (long i = start; i < end; i++) {
			if (!(getHexDigit(i) == b.getHexDigit(i))) return i - start;
		}
		return length;
	}
	
	@Override
	public Blob toFlatBlob() {
		return getEncoding().slice(HEADER_LENGTH,HEADER_LENGTH+LENGTH);
	}
}
