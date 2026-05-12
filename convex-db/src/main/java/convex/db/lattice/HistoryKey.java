package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.Blob;

/**
 * Encoding and decoding utilities for history index keys.
 *
 * <p>Key format: {@code [1 byte: pkLength] [pkLength bytes: pk] [8 bytes: nanotime big-endian]}
 *
 * <p>The 1-byte length prefix supports pk blobs up to 255 bytes, covering all practical
 * key types (CVMLong = 8 bytes, typical strings well within 255).
 *
 * <p>Big-endian nanotime ensures that entries for the same pk are sorted chronologically
 * in the radix tree — {@code forEach} yields them oldest-first with no extra sorting.
 */
public class HistoryKey {

	static final int NANOTIME_BYTES = 8;
	static final int LENGTH_BYTES = 1;

	private HistoryKey() {}

	/**
	 * Creates a history index key from a pk blob and a nanotime.
	 *
	 * @param pk      Primary key blob (max 255 bytes)
	 * @param nanotime Monotonic timestamp from {@link System#nanoTime()}
	 * @return History key blob
	 */
	public static ABlob of(ABlob pk, long nanotime) {
		int pkLen = (int) pk.count();
		if (pkLen > 255) throw new IllegalArgumentException("Primary key too long: " + pkLen);
		byte[] key = new byte[LENGTH_BYTES + pkLen + NANOTIME_BYTES];
		key[0] = (byte) pkLen;
		for (int i = 0; i < pkLen; i++) key[LENGTH_BYTES + i] = byteAt(pk, i);
		long v = nanotime;
		for (int i = NANOTIME_BYTES - 1; i >= 0; i--) { key[LENGTH_BYTES + pkLen + i] = (byte)(v & 0xFF); v >>>= 8; }
		return Blob.wrap(key);
	}

	/**
	 * Returns the prefix blob used to scan all history entries for a given pk.
	 * This is the {@code [pkLength | pk bytes]} portion without the nanotime suffix.
	 *
	 * @param pk Primary key blob
	 * @return Prefix blob for radix-tree iteration
	 */
	public static ABlob prefix(ABlob pk) {
		int pkLen = (int) pk.count();
		byte[] prefix = new byte[LENGTH_BYTES + pkLen];
		prefix[0] = (byte) pkLen;
		for (int i = 0; i < pkLen; i++) prefix[LENGTH_BYTES + i] = byteAt(pk, i);
		return Blob.wrap(prefix);
	}

	/**
	 * Returns true if {@code hkey} starts with all nibbles of {@code pkPrefix}.
	 * Used to filter history entries belonging to a specific pk during iteration.
	 */
	public static boolean hasPrefix(ABlob hkey, ABlob pkPrefix) {
		long pNibbles = pkPrefix.hexLength();
		if (hkey.hexLength() < pNibbles) return false;
		for (long i = 0; i < pNibbles; i++) {
			if (hkey.getHexDigit(i) != pkPrefix.getHexDigit(i)) return false;
		}
		return true;
	}

	/**
	 * Extracts the nanotime from a history key.
	 *
	 * @param hkey History key blob
	 * @return Nanotime value embedded in the key
	 */
	public static long extractNanotime(ABlob hkey) {
		// First byte is pkLength; nanotime starts at byte (1 + pkLength)
		int pkLen = (hkey.getHexDigit(0) << 4) | hkey.getHexDigit(1);
		long ts = 0;
		int startNibble = 2 + 2 * pkLen; // skip [length byte] + [pk bytes]
		for (int i = 0; i < 16; i++) ts = (ts << 4) | hkey.getHexDigit(startNibble + i);
		return ts;
	}

	/** Reads byte {@code byteIndex} from a blob using nibble-level access. */
	static byte byteAt(ABlob blob, int byteIndex) {
		int ni = byteIndex * 2;
		return (byte)((blob.getHexDigit(ni) << 4) | blob.getHexDigit(ni + 1));
	}
}
