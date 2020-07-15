package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Abstract base class for binary data stored in Java arrays.
 *
 */
public abstract class AArrayBlob extends ABlob {
	protected final byte[] store;
	protected final int offset;
	protected final int length;

	protected AArrayBlob(byte[] bytes, int offset, int length) {
		this.store = bytes;
		this.length = length;
		this.offset = offset;
	}

	@Override
	public void updateDigest(MessageDigest digest) {
		digest.update(store, offset, length);
	}

	@Override
	public Blob slice(long start, long length) {
		if (length < 0) throw new IllegalArgumentException(Errors.negativeLength(length));
		if (start < 0) throw new IndexOutOfBoundsException("Start out of bounds: " + start);
		if ((start + length) > this.length)
			throw new IndexOutOfBoundsException("End out of bounds: " + (start + length));
		return Blob.wrap(store, Utils.checkedInt(start + offset), Utils.checkedInt(length));
	}

	@Override
	public ABlob append(ABlob d) {
		int dlength = Utils.checkedInt(d.length());
		if (dlength == 0) return this;
		int length = this.length;
		if (length == 0) return d;
		byte[] newData = new byte[length + dlength];
		getBytes(newData, 0);
		d.getBytes(newData, length);
		return Blob.wrap(newData);
	}

	@Override
	public Blob slice(long start) {
		return slice(start, length() - start);
	}

	@Override
	public Blob toBlob() {
		return Blob.wrap(store, offset, length);
	}

	@Override
	public final int compareTo(ABlob b) {
		if (b instanceof AArrayBlob) {
			return compareTo((AArrayBlob) b);
		} else {
			return compareTo(b.toBlob());
		}
	}

	public final int compareTo(AArrayBlob b) {
		if (this == b) return 0;
		int alength = this.length;
		int blength = b.length;
		int compareLength = Math.min(alength, blength);
		int c = Utils.compareByteArrays(this.store, this.offset, b.store, b.offset, compareLength);
		if (c != 0) return c;
		if (alength > compareLength) return 1; // this is bigger
		if (blength > compareLength) return -1; // b is bigger
		return 0;
	}

	@Override
	public final void getBytes(byte[] dest, int destOffset) {
		System.arraycopy(store, offset, dest, destOffset, length);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		return bb.put(store, offset, length);
	}

	@Override
	public final String toHexString() {
		return Utils.toHexString(store, offset, length);
	}

	@Override
	public void toHexString(StringBuilder sb) {
		sb.append(toHexString());
	}

	@Override
	public final long length() {
		return length;
	}

	@Override
	public int hashCode() {
		return getHash().hashCode();
	}

	@Override
	public byte get(long i) {
		int ix = (int) i;
		if ((ix != i) || (ix < 0) || (ix >= length)) {
			throw new IndexOutOfBoundsException("Index: " + i);
		}
		return store[offset + ix];
	}

	public int getHexDigit(long digitPos) {
		byte b = get(digitPos >> 1);
		if ((digitPos & 1) == 0) {
			return (b >> 4) & 0x0F; // first hex digit
		} else {
			return b & 0x0F; // second hex digit
		}
	}

	public Hash extractHash(int offset, int length) {
		return Hash.wrap(store, this.offset + offset, length);
	}

	public byte[] getInternalArray() {
		return store;
	}

	@Override
	public ByteBuffer getByteBuffer() {
		return ByteBuffer.wrap(store, offset, length).asReadOnlyBuffer();
	}

	public int getOffset() {
		return offset;
	}

	public boolean equals(byte[] bytes, int byteOffset) {
		return Utils.arrayEquals(store, offset, bytes, byteOffset, length);
	}

	@Override
	public long commonHexPrefixLength(ABlob b) {
		if (b == this) return length() * 2;

		long max = Math.min(length(), b.length());
		for (long i = 0; i < max; i++) {
			byte ai = get(i);
			byte bi = b.get(i);
			if (ai != bi) return (i * 2) + (Utils.firstDigitMatch(ai, bi) ? 1 : 0);
		}
		return max * 2;
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
	public void validate() throws InvalidDataException {
		super.validate();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length < 0) throw new InvalidDataException("Negative length: " + length, this);
		if (offset < 0) throw new InvalidDataException("Negative data offset: " + offset, this);
		if ((offset + length) > store.length) {
			throw new InvalidDataException(
					"End out of range: " + (offset + length) + " with array size=" + store.length, this);
		}
	}

	@Override
	public long longValue() {
		long c = length();
		if (c != 8) throw new IllegalStateException(Errors.wrongLength(8, c));
		return Utils.readLong(store, offset);
	}

}