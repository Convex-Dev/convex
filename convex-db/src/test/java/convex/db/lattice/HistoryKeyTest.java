package convex.db.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.prim.CVMLong;

/**
 * Unit tests for HistoryKey encoding, prefix matching and nanotime extraction.
 */
public class HistoryKeyTest {

	/** Encodes a CVMLong pk to blob the same way SQLSchema does. */
	static ABlob pkBlob(long id) {
		return CVMLong.create(id).getEncoding();
	}

	// ── Key construction and round-trip ──────────────────────────────────────

	@Test
	void testKeyLength() {
		ABlob pk = pkBlob(1L);
		ABlob key = HistoryKey.of(pk, 12345L);
		// 1 byte length + pkLen bytes + 8 bytes nanotime
		assertEquals(1 + pk.count() + 8, key.count());
	}

	@Test
	void testExtractNanotime() {
		ABlob pk = pkBlob(42L);
		long nanotime = 0xDEADBEEFCAFEL;
		ABlob key = HistoryKey.of(pk, nanotime);
		assertEquals(nanotime, HistoryKey.extractNanotime(key));
	}

	@Test
	void testNanotimeZero() {
		ABlob pk = pkBlob(1L);
		ABlob key = HistoryKey.of(pk, 0L);
		assertEquals(0L, HistoryKey.extractNanotime(key));
	}

	@Test
	void testNanotimeMaxLong() {
		ABlob pk = pkBlob(1L);
		ABlob key = HistoryKey.of(pk, Long.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, HistoryKey.extractNanotime(key));
	}

	@Test
	void testDifferentPksDifferentKeys() {
		ABlob key1 = HistoryKey.of(pkBlob(1L), 100L);
		ABlob key2 = HistoryKey.of(pkBlob(2L), 100L);
		assertFalse(key1.equals(key2));
	}

	@Test
	void testSamePkDifferentNanotimeDifferentKeys() {
		ABlob pk = pkBlob(1L);
		ABlob key1 = HistoryKey.of(pk, 100L);
		ABlob key2 = HistoryKey.of(pk, 200L);
		assertFalse(key1.equals(key2));
	}

	@Test
	void testSamePkSameNanotimeSameKey() {
		ABlob pk = pkBlob(5L);
		ABlob key1 = HistoryKey.of(pk, 999L);
		ABlob key2 = HistoryKey.of(pk, 999L);
		assertEquals(key1, key2);
	}

	// ── Chronological ordering ────────────────────────────────────────────────

	@Test
	void testChronologicalOrdering() {
		// Big-endian nanotime means lexicographic order == time order for same pk
		ABlob pk = pkBlob(1L);
		ABlob early = HistoryKey.of(pk, 1000L);
		ABlob late  = HistoryKey.of(pk, 2000L);
		// Lexicographic comparison: early < late
		assertTrue(early.compareTo(late) < 0);
	}

	// ── Prefix matching ───────────────────────────────────────────────────────

	@Test
	void testPrefixLength() {
		ABlob pk = pkBlob(1L);
		ABlob prefix = HistoryKey.prefix(pk);
		assertEquals(1 + pk.count(), prefix.count());
	}

	@Test
	void testHasPrefixTrue() {
		ABlob pk = pkBlob(7L);
		ABlob key = HistoryKey.of(pk, 12345L);
		ABlob prefix = HistoryKey.prefix(pk);
		assertTrue(HistoryKey.hasPrefix(key, prefix));
	}

	@Test
	void testHasPrefixFalseWrongPk() {
		ABlob pk1 = pkBlob(1L);
		ABlob pk2 = pkBlob(2L);
		ABlob key = HistoryKey.of(pk1, 12345L);
		ABlob prefix2 = HistoryKey.prefix(pk2);
		assertFalse(HistoryKey.hasPrefix(key, prefix2));
	}

	@Test
	void testHasPrefixFalseKeyTooShort() {
		ABlob pk = pkBlob(1L);
		ABlob prefix = HistoryKey.prefix(pk);
		// A blob shorter than the prefix cannot match
		ABlob shortBlob = Blob.wrap(new byte[]{0x01});
		assertFalse(HistoryKey.hasPrefix(shortBlob, prefix));
	}

	@Test
	void testPkTooLongThrows() {
		byte[] longPk = new byte[256]; // exceeds 255-byte limit
		ABlob pk = Blob.wrap(longPk);
		assertThrows(IllegalArgumentException.class, () -> HistoryKey.of(pk, 0L));
	}

	@Test
	void testPk255BytesAccepted() {
		byte[] maxPk = new byte[255];
		ABlob pk = Blob.wrap(maxPk);
		ABlob key = HistoryKey.of(pk, 42L);
		assertEquals(1 + 255 + 8, key.count());
		assertEquals(42L, HistoryKey.extractNanotime(key));
	}
}
