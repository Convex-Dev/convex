package convex.core.crypto.siphash;

import java.util.Objects;

/**
 * Fast, allocation-free SipHash-2-4.
 *
 * <p>The 128-bit key is interpreted as two little-endian {@code long} values.
 * This class is stateless and safe for concurrent use.</p>
 */
public final class SipHash {

	private static final long V0 = 0x736f6d6570736575L;
	private static final long V1 = 0x646f72616e646f6dL;
	private static final long V2 = 0x6c7967656e657261L;
	private static final long V3 = 0x7465646279746573L;

	private SipHash() {
	}

	/**
	 * Computes SipHash-2-4 using a 16-byte key.
	 *
	 * @param key 16 key bytes in little-endian order
	 * @param data bytes to hash
	 * @return 64-bit SipHash value
	 */
	public static long hash(byte[] key, byte[] data) {
		if (key.length != 16) throw new IllegalArgumentException("Key must be exactly 16 bytes");
		return hash(readLittleEndian(key, 0), readLittleEndian(key, 8), data, 0, data.length);
	}

	/**
	 * Computes SipHash-2-4 over an entire byte array.
	 *
	 * @param k0 low 64 bits of the key
	 * @param k1 high 64 bits of the key
	 * @param data bytes to hash
	 * @return 64-bit SipHash value
	 */
	public static long hash(long k0, long k1, byte[] data) {
		return hash(k0, k1, data, 0, data.length);
	}

	/**
	 * Computes SipHash-2-4 over a byte-array range without copying it.
	 *
	 * @param k0 low 64 bits of the key
	 * @param k1 high 64 bits of the key
	 * @param data source bytes
	 * @param offset first byte to hash
	 * @param length number of bytes to hash
	 * @return 64-bit SipHash value
	 */
	public static long hash(long k0, long k1, byte[] data, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, data.length);

		long v0 = V0 ^ k0;
		long v1 = V1 ^ k1;
		long v2 = V2 ^ k0;
		long v3 = V3 ^ k1;

		int i = offset;
		int end = offset + (length & ~7);
		while (i < end) {
			long m = readLittleEndian(data, i);
			i += 8;
			v3 ^= m;

			for (int round = 0; round < 2; round++) {
				v0 += v1;
				v2 += v3;
				v1 = Long.rotateLeft(v1, 13) ^ v0;
				v3 = Long.rotateLeft(v3, 16) ^ v2;
				v0 = Long.rotateLeft(v0, 32);
				v2 += v1;
				v0 += v3;
				v1 = Long.rotateLeft(v1, 17) ^ v2;
				v3 = Long.rotateLeft(v3, 21) ^ v0;
				v2 = Long.rotateLeft(v2, 32);
			}
			v0 ^= m;
		}

		long last = (long) length << 56;
		switch (length & 7) {
			case 7: last |= (data[end + 6] & 0xffL) << 48;
			case 6: last |= (data[end + 5] & 0xffL) << 40;
			case 5: last |= (data[end + 4] & 0xffL) << 32;
			case 4: last |= (data[end + 3] & 0xffL) << 24;
			case 3: last |= (data[end + 2] & 0xffL) << 16;
			case 2: last |= (data[end + 1] & 0xffL) << 8;
			case 1: last |= data[end] & 0xffL;
			default:
		}

		v3 ^= last;
		for (int round = 0; round < 2; round++) {
			v0 += v1;
			v2 += v3;
			v1 = Long.rotateLeft(v1, 13) ^ v0;
			v3 = Long.rotateLeft(v3, 16) ^ v2;
			v0 = Long.rotateLeft(v0, 32);
			v2 += v1;
			v0 += v3;
			v1 = Long.rotateLeft(v1, 17) ^ v2;
			v3 = Long.rotateLeft(v3, 21) ^ v0;
			v2 = Long.rotateLeft(v2, 32);
		}
		v0 ^= last;

		v2 ^= 0xffL;
		for (int round = 0; round < 4; round++) {
			v0 += v1;
			v2 += v3;
			v1 = Long.rotateLeft(v1, 13) ^ v0;
			v3 = Long.rotateLeft(v3, 16) ^ v2;
			v0 = Long.rotateLeft(v0, 32);
			v2 += v1;
			v0 += v3;
			v1 = Long.rotateLeft(v1, 17) ^ v2;
			v3 = Long.rotateLeft(v3, 21) ^ v0;
			v2 = Long.rotateLeft(v2, 32);
		}

		return v0 ^ v1 ^ v2 ^ v3;
	}

	private static long readLittleEndian(byte[] data, int offset) {
		return (data[offset] & 0xffL)
				| (data[offset + 1] & 0xffL) << 8
				| (data[offset + 2] & 0xffL) << 16
				| (data[offset + 3] & 0xffL) << 24
				| (data[offset + 4] & 0xffL) << 32
				| (data[offset + 5] & 0xffL) << 40
				| (data[offset + 6] & 0xffL) << 48
				| (data[offset + 7] & 0xffL) << 56;
	}
}
