package convex.db.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;

/**
 * Tests for SegmentedEtchStore: write/read across segments, seal rotation,
 * compaction status tracking, and online sealed-segment compaction.
 *
 * <p>Tests use Blob values (non-embedded) so cells are actually written to the
 * Etch file. Each test calls setRootData to establish a root, which is required
 * for root-based compaction to traverse the reachable cell tree.
 */
public class SegmentedEtchStoreTest {

	private File dir;
	private SegmentedEtchStore store;

	@BeforeEach
	void setup() throws IOException {
		dir = Files.createTempDirectory("segmented-etch-test-").toFile();
		store = SegmentedEtchStore.createFresh(dir);
	}

	@AfterEach
	void teardown() {
		if (store != null) store.close();
		File[] files = dir.listFiles();
		if (files != null) for (File f : files) f.delete();
		dir.delete();
	}

	/** Creates a non-embedded Blob of given size, suitable for Etch persistence. */
	static Blob makeBlob(int size, byte fill) {
		byte[] bs = new byte[size];
		for (int i = 0; i < size; i++) bs[i] = fill;
		return Blob.wrap(bs);
	}

	// ── Basic write/read ──────────────────────────────────────────────────────

	@Test
	void testWriteAndReadFromHot() throws IOException {
		ACell val = makeBlob(64, (byte) 1);
		store.setRootData(val);

		Ref<?> found = store.refForHash(Hash.get(val));
		assertNotNull(found);
		assertEquals(val, found.getValue());
	}

	@Test
	void testMissingReturnsNull() throws IOException {
		ACell other = makeBlob(64, (byte) 99);
		assertNull(store.refForHash(Hash.get(other)));
	}

	// ── Seal rotation ─────────────────────────────────────────────────────────

	@Test
	void testSealCreatesWarmFile() throws IOException {
		store.setRootData(makeBlob(64, (byte) 1));
		assertEquals(0, store.getSealedCount());
		store.sealHot();
		assertEquals(1, store.getSealedCount());
		assertTrue(store.getHotFile().exists());
	}

	@Test
	void testReadFromSealedAfterRotation() throws IOException {
		ACell val = makeBlob(64, (byte) 7);
		store.setRootData(val);
		store.sealHot(); // val is now in a sealed segment

		Ref<?> found = store.refForHash(Hash.get(val));
		assertNotNull(found, "Should still find value after sealing");
		assertEquals(val, found.getValue());
	}

	@Test
	void testMultipleSealedSegments() throws IOException {
		ACell v1 = makeBlob(64, (byte) 1);
		ACell v2 = makeBlob(64, (byte) 2);
		ACell v3 = makeBlob(64, (byte) 3);

		store.setRootData(v1);
		store.sealHot();
		store.setRootData(v2);
		store.sealHot();
		store.setRootData(v3); // in hot

		assertEquals(2, store.getSealedCount());
		assertNotNull(store.refForHash(Hash.get(v1)), "v1 in oldest sealed");
		assertNotNull(store.refForHash(Hash.get(v2)), "v2 in newest sealed");
		assertNotNull(store.refForHash(Hash.get(v3)), "v3 in hot");
	}

	// ── Compaction status ─────────────────────────────────────────────────────

	@Test
	void testNeedsCompactionAfterSeal() throws IOException {
		store.setRootData(makeBlob(64, (byte) 1));
		store.sealHot();

		assertTrue(store.needsCompaction(0), "Fresh sealed segment needs compaction");
		assertFalse(store.isCompacted(0));
	}

	@Test
	void testIsCompactedAfterCompaction() throws IOException {
		store.setRootData(makeBlob(64, (byte) 1));
		store.sealHot();
		store.compactSealed(0);

		assertTrue(store.isCompacted(0));
		assertFalse(store.needsCompaction(0));
	}

	@Test
	void testCompactedFilenameHasSuffix() throws IOException {
		store.setRootData(makeBlob(64, (byte) 1));
		store.sealHot();
		store.compactSealed(0);

		File compactedFile = store.getSealed().get(0).getFile();
		assertTrue(compactedFile.getName().endsWith("-c.etch"),
				"Compacted file should have -c.etch suffix, got: " + compactedFile.getName());
	}

	@Test
	void testOriginalFileDeletedAfterCompaction() throws IOException {
		store.setRootData(makeBlob(64, (byte) 1));
		store.sealHot();

		File originalFile = store.getSealed().get(0).getFile();
		store.compactSealed(0);

		assertFalse(originalFile.exists(), "Original uncompacted file should be deleted");
	}

	// ── Data integrity after compaction ───────────────────────────────────────

	@Test
	void testReadFromCompactedSegment() throws IOException {
		ACell val = makeBlob(64, (byte) 42);
		store.setRootData(val);
		store.sealHot();
		store.compactSealed(0);

		Ref<?> found = store.refForHash(Hash.get(val));
		assertNotNull(found, "Value should survive compaction");
		assertEquals(val, found.getValue());
	}

	@Test
	void testCompactAllSealed() throws IOException {
		ACell v1 = makeBlob(64, (byte) 10);
		ACell v2 = makeBlob(64, (byte) 20);

		store.setRootData(v1);
		store.sealHot();
		store.setRootData(v2);
		store.sealHot();

		store.compactAllSealed();

		assertTrue(store.isCompacted(0));
		assertTrue(store.isCompacted(1));
		assertNotNull(store.refForHash(Hash.get(v1)));
		assertNotNull(store.refForHash(Hash.get(v2)));
	}

	@Test
	void testCompactSealedIsIdempotent() throws IOException {
		store.setRootData(makeBlob(64, (byte) 1));
		store.sealHot();
		store.compactSealed(0);
		// Second call should be a no-op — no exception, still readable
		store.compactSealed(0);

		assertTrue(store.isCompacted(0));
	}

	// ── Reopen ────────────────────────────────────────────────────────────────

	@Test
	void testReopenRecognisesCompactedSegments() throws IOException {
		ACell val = makeBlob(64, (byte) 55);
		store.setRootData(val);
		store.sealHot();
		store.compactSealed(0);
		store.close();

		// Reopen — compacted segment (-c.etch) should be picked up
		store = SegmentedEtchStore.open(dir);
		assertEquals(1, store.getSealedCount());
		assertTrue(store.isCompacted(0));
		assertNotNull(store.refForHash(Hash.get(val)));
	}
}
