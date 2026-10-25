package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.fs.impl.DLFSLocal;

/**
 * Cross-version DLFS format fixtures. Upgrading DLFS to modification-time support is
 * one-way (CAD028, Compatibility): current readers accept legacy nodes unchanged, but the
 * first ordinary write extends every node on its path, so earlier readers reject the drive.
 *
 * <p>{@code dlfs-0.8.17.cad3} is the multi-cell encoding of a drive root written by the
 * released convex-core 0.8.17 node writer ({@code createDirectory}, {@code createEmptyFile},
 * {@code updateNode}, {@code deleteNode}, {@code withMetadata}) with explicit operation times:
 * /docs (1100), /docs/readme.txt "Hello DLFS" (1200, metadata "text/plain" at 1500),
 * /docs/big.bin of 10000 bytes where byte i is {@code (byte)(i*31+7)} (1300), /empty.txt (1400),
 * /trash (1600) with old.txt written at 1650 and deleted at 1700, and /empty-dir (1800).</p>
 *
 * <p>{@code dlfs-0.8.18.cad3} is that drive after these operations through the DLFS NIO file
 * system with a fixed clock: write "Hello again" to readme.txt (2000), set big.bin's last
 * modified time to 42 (2100), move /empty.txt to /trash/moved.txt (2200), create /new-dir and
 * write /new-dir/note.txt (2300), and delete note.txt (2400). It was generated at 354b15b22.
 * A failure here means the DLFS writer's node format changed: regenerate it, and update
 * {@code CURRENT_HASH}, only as a deliberate format decision.</p>
 */
public class DLFSCompatibilityTest {
	/** Longest node accepted by readers without modification-time support (0.8.17 isValidNodeShallow) */
	private static final long LEGACY_MAX_FIELDS = 5;

	private static final Hash LEGACY_HASH = Hash.fromHex("ab48979ecc509198b732378758c8052ad9bcbc56bf15feaeec38bf8965061485");
	private static final Hash CURRENT_HASH = Hash.fromHex("4078d8210c414d6a8ec5af105545ca0b1c855080529e1e2e539f5a1ca8db24e9");

	private static AVector<ACell> load(String resource) throws Exception {
		try (InputStream is = DLFSCompatibilityTest.class.getResourceAsStream(resource)) {
			assertNotNull(is, resource + " not found in test resources");
			return new MemoryStore().decodeMultiCell(Blob.wrap(is.readAllBytes()));
		}
	}

	private static byte[] bigData() {
		byte[] big = new byte[10000];
		for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31 + 7);
		return big;
	}

	private static long mtime(DLFSLocal fs, String path) throws Exception {
		return Files.getLastModifiedTime(fs.getPath(path)).toMillis();
	}

	private static List<String> list(DLFSLocal fs, String path) throws Exception {
		try (Stream<java.nio.file.Path> entries = Files.list(fs.getPath(path))) {
			return entries.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@SuppressWarnings("unchecked")
	private static long maxFields(AVector<ACell> node) {
		long max = node.count();
		if (node.get(DLFSNode.POS_DIR) instanceof Index<?, ?> entries) {
			for (long i = 0; i < entries.count(); i++) {
				MapEntry<?, ?> e = entries.entryAt(i);
				max = Math.max(max, maxFields((AVector<ACell>) e.getValue()));
			}
		}
		return max;
	}

	@Test
	public void testLegacyDriveReadsUnchanged() throws Exception {
		AVector<ACell> root = load("/dlfs-0.8.17.cad3");
		assertEquals(LEGACY_HASH, root.getHash());
		assertTrue(DLFSNode.isValidTree(root));
		assertEquals(LEGACY_MAX_FIELDS, maxFields(root), "fixture holds only legacy node shapes");

		try (DLFSLocal fs = DLFS.create()) {
			fs.getCursor().set(root);
			assertEquals(List.of("docs", "empty-dir", "empty.txt", "trash"), list(fs, "/"));
			assertEquals("Hello DLFS", Files.readString(fs.getPath("/docs/readme.txt")));
			assertEquals(Strings.create("text/plain"), DLFSNode.getMetadata(fs.getNode(fs.getPath("/docs/readme.txt"))));
			assertArrayEquals(bigData(), Files.readAllBytes(fs.getPath("/docs/big.bin")));
			assertEquals(0, Files.size(fs.getPath("/empty.txt")));
			assertEquals(List.of(), list(fs, "/empty-dir"));
			assertEquals(List.of(), list(fs, "/trash"));
			assertFalse(Files.exists(fs.getPath("/trash/old.txt")));
			assertEquals(CVMLong.create(1700), DLFSNode.getTombstones(fs.getNode(fs.getPath("/trash"))).get(Strings.create("old.txt")));

			// Legacy nodes have no mtime: it falls back to update time
			assertEquals(1800, mtime(fs, "/"));
			assertEquals(1500, mtime(fs, "/docs"));
			assertEquals(1500, mtime(fs, "/docs/readme.txt"));
			assertEquals(1300, mtime(fs, "/docs/big.bin"));
			assertEquals(1700, mtime(fs, "/trash"));

			assertSame(root, fs.getCursor().get(), "reading must not rewrite legacy nodes");
		}
	}

	@Test
	public void testFirstWriteExtendsPathToRoot() throws Exception {
		AVector<ACell> legacy = load("/dlfs-0.8.17.cad3");
		try (DLFSLocal fs = DLFS.create()) {
			fs.getCursor().set(legacy);
			fs.setClock(() -> CVMLong.create(2000));
			Files.writeString(fs.getPath("/docs/readme.txt"), "Hello again");
			AVector<ACell> root = fs.getCursor().get();

			// Every node on the written path now exceeds the legacy shape, so earlier readers reject the drive
			for (String path : List.of("/", "/docs", "/docs/readme.txt")) {
				assertTrue(fs.getNode(fs.getPath(path)).count() > LEGACY_MAX_FIELDS, path);
			}
			// Ancestors keep their modification time while their update time advances
			assertEquals(2000, DLFSNode.getUTime(root).longValue());
			assertEquals(1800, mtime(fs, "/"));
			assertEquals(1500, mtime(fs, "/docs"));
			assertEquals(2000, mtime(fs, "/docs/readme.txt"));

			// Untouched subtrees are shared, not rewritten
			for (String path : List.of("/docs/big.bin", "/empty.txt", "/empty-dir", "/trash")) {
				DLPath p = fs.getPath(path);
				assertSame(DLFSNode.navigate(legacy, p), fs.getNode(p), path);
			}
		}
	}

	@Test
	public void testCurrentDriveReads() throws Exception {
		AVector<ACell> root = load("/dlfs-0.8.18.cad3");
		assertEquals(CURRENT_HASH, root.getHash());
		assertTrue(DLFSNode.isValidTree(root));

		try (DLFSLocal fs = DLFS.create()) {
			fs.getCursor().set(root);
			assertEquals(List.of("docs", "empty-dir", "new-dir", "trash"), list(fs, "/"));
			assertEquals("Hello again", Files.readString(fs.getPath("/docs/readme.txt")));
			assertEquals(Strings.create("text/plain"), DLFSNode.getMetadata(fs.getNode(fs.getPath("/docs/readme.txt"))));
			assertEquals(2000, mtime(fs, "/docs/readme.txt"));

			// An explicit older modification time is kept apart from the merge clock
			assertArrayEquals(bigData(), Files.readAllBytes(fs.getPath("/docs/big.bin")));
			assertEquals(42, mtime(fs, "/docs/big.bin"));
			assertEquals(2100, DLFSNode.getUTime(fs.getNode(fs.getPath("/docs/big.bin"))).longValue());

			// A move preserves modification time and leaves a tombstone at the source
			assertEquals(List.of("moved.txt"), list(fs, "/trash"));
			assertEquals(1400, mtime(fs, "/trash/moved.txt"));
			assertEquals(2200, DLFSNode.getUTime(fs.getNode(fs.getPath("/trash/moved.txt"))).longValue());
			assertEquals(CVMLong.create(2200), DLFSNode.getTombstones(root).get(Strings.create("empty.txt")));

			assertEquals(List.of(), list(fs, "/new-dir"));
			assertEquals(2400, mtime(fs, "/new-dir"));
			assertEquals(CVMLong.create(2400), DLFSNode.getTombstones(fs.getNode(fs.getPath("/new-dir"))).get(Strings.create("note.txt")));

			assertSame(root, fs.getCursor().get(), "reading must not rewrite nodes");
		}
	}

	@Test
	public void testUpgradedReplicaAcceptsLegacyState() throws Exception {
		AVector<ACell> legacy = load("/dlfs-0.8.17.cad3");
		AVector<ACell> current = load("/dlfs-0.8.18.cad3");
		assertEquals(current, DLFSLattice.INSTANCE.merge(current, legacy));
		assertEquals(current, DLFSLattice.INSTANCE.merge(legacy, current));
	}
}
