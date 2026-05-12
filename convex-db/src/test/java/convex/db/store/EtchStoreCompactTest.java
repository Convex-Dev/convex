package convex.db.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;

/**
 * Tests for {@link EtchStore#compact(File)}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Cells reachable from the root survive compaction</li>
 *   <li>The compacted file is no larger than the source</li>
 *   <li>After many writes the compacted file is strictly smaller</li>
 *   <li>The source file is not modified</li>
 * </ul>
 */
public class EtchStoreCompactTest {

	private EtchStore src;
	private EtchStore dst;
	private File srcFile;
	private File dstFile;

	@AfterEach
	void teardown() {
		if (src != null) src.close();
		if (dst != null) dst.close();
		if (srcFile != null && srcFile.exists()) srcFile.delete();
		if (dstFile != null && dstFile.exists()) dstFile.delete();
	}

	EtchStore openTemp(String prefix) throws IOException {
		File f = File.createTempFile(prefix, ".etch");
		f.deleteOnExit();
		if (prefix.equals("src")) srcFile = f;
		else dstFile = f;
		return EtchStore.create(f);
	}

	/** Returns the data length recorded in the Etch file header. */
	static long etchDataBytes(File file) throws Exception {
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			raf.seek(4);
			return raf.readLong();
		}
	}

	// ── Basic survival ────────────────────────────────────────────────────────

	@Test
	void testRootSurvivesCompaction() throws Exception {
		src = openTemp("src");
		dstFile = File.createTempFile("dst", ".etch");
		dstFile.deleteOnExit();

		ACell root = Blob.wrap(new byte[128]);
		src.setRootData(root);

		dst = src.compact(dstFile);

		Ref<?> found = dst.refForHash(Hash.get(root));
		assertNotNull(found);
		assertEquals(root, found.getValue());
	}

	@Test
	void testNestedCellsSurviveCompaction() throws Exception {
		src = openTemp("src");
		dstFile = File.createTempFile("dst", ".etch");
		dstFile.deleteOnExit();

		// Use blobs larger than MAX_EMBEDDED_LENGTH (140 bytes) so they are stored
		// as separate non-embedded cells rather than inlined in the vector encoding.
		ACell b1 = Blob.wrap(new byte[200]);
		ACell b2 = Blob.wrap(new byte[200]);
		ACell root = Vectors.of(b1, b2);
		src.setRootData(root);

		dst = src.compact(dstFile);

		assertNotNull(dst.refForHash(Hash.get(root)));
		assertNotNull(dst.refForHash(Hash.get(b1)));
		assertNotNull(dst.refForHash(Hash.get(b2)));
	}

	@Test
	void testRootHashPreserved() throws Exception {
		src = openTemp("src");
		dstFile = File.createTempFile("dst", ".etch");
		dstFile.deleteOnExit();

		ACell root = CVMLong.create(42L);
		src.setRootData(root);

		dst = src.compact(dstFile);

		ACell dstRoot = dst.getRootData();
		assertEquals(root, dstRoot);
	}

	// ── File size ─────────────────────────────────────────────────────────────

	@Test
	void testCompactedFileNoLargerThanSource() throws Exception {
		src = openTemp("src");
		dstFile = File.createTempFile("dst", ".etch");
		dstFile.deleteOnExit();

		ACell root = Blob.wrap(new byte[256]);
		src.setRootData(root);

		long srcBytes = etchDataBytes(srcFile);
		dst = src.compact(dstFile);
		dst.close(); dst = null;
		long dstBytes = etchDataBytes(dstFile);

		assertTrue(dstBytes <= srcBytes,
				"Compacted file (" + dstBytes + ") should be <= source (" + srcBytes + ")");
	}

	@Test
	void testCompactionReducesSizeAfterManyWrites() throws Exception {
		src = openTemp("src");
		dstFile = File.createTempFile("dst", ".etch");
		dstFile.deleteOnExit();

		// Write many different roots to accumulate dead cells
		for (int i = 0; i < 100; i++) {
			src.setRootData(Blob.wrap(new byte[256]));
		}
		// Final root is a distinct value
		ACell finalRoot = Vectors.of(CVMLong.create(999L));
		src.setRootData(finalRoot);
		src.flush();

		long srcBytes = etchDataBytes(srcFile);
		dst = src.compact(dstFile);
		dst.close(); dst = null;
		long dstBytes = etchDataBytes(dstFile);

		assertTrue(dstBytes < srcBytes,
				"Compacted file (" + dstBytes + ") should be smaller than bloated source (" + srcBytes + ")");
		assertEquals(finalRoot, EtchStore.create(dstFile).getRootData());
	}

	// ── Source unchanged ──────────────────────────────────────────────────────

	@Test
	void testSourceFileUnchangedAfterCompaction() throws Exception {
		src = openTemp("src");
		dstFile = File.createTempFile("dst", ".etch");
		dstFile.deleteOnExit();

		ACell root = Blob.wrap(new byte[128]);
		src.setRootData(root);
		long srcBytesBefore = etchDataBytes(srcFile);

		dst = src.compact(dstFile);

		long srcBytesAfter = etchDataBytes(srcFile);
		assertEquals(srcBytesBefore, srcBytesAfter, "Source file must not be modified during compaction");
	}
}
