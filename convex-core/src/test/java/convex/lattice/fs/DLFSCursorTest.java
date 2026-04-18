package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.fs.impl.DLFSLocal;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Tests for DLFS integration with lattice cursors.
 *
 * Demonstrates the recommended API for:
 * - Creating standalone DLFS drives via {@code DLFS.create()}
 * - Connecting DLFS drives to a parent lattice via {@code DLFS.connect()}
 * - Forking drives for isolated batch operations
 * - Syncing changes back via lattice merge
 */
public class DLFSCursorTest {

	@Test
	public void testStandaloneDLFS() throws Exception {
		// Create a standalone DLFS drive with its own lattice cursor
		DLFSLocal dlfs = DLFS.create();

		// Create directory and file via NIO
		Path testDir = dlfs.getPath("/docs");
		Files.createDirectory(testDir);

		Path file = dlfs.getPath("/docs/readme.txt");
		Files.writeString(file, "Hello from DLFS!");

		// Verify file content
		assertEquals("Hello from DLFS!", Files.readString(file));
		assertTrue(Files.isDirectory(testDir));
	}

	@Test
	public void testConnectedDLFS() throws Exception {
		// Create a root lattice cursor simulating a drives map: {driveName -> DLFSNode}
		MapLattice<AString, AVector<ACell>> drivesLattice = MapLattice.create(DLFSLattice.INSTANCE);
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> root = Cursors.createLattice(drivesLattice);

		// Connect a named drive to the root
		AString driveName = Strings.create("test");
		DLFSLocal dlfs = DLFS.connect(root, driveName);

		// Write through NIO
		Path testDir = dlfs.getPath("/docs");
		Files.createDirectory(testDir);
		Path file = dlfs.getPath("/docs/readme.txt");
		Files.writeString(file, "Connected drive!");

		// Verify the root cursor received the update
		AHashMap<AString, AVector<ACell>> rootMap = root.get();
		assertTrue(rootMap.containsKey(driveName), "Root should contain drive entry");

		// Verify via a second connected drive reading the same path
		DLFSLocal dlfs2 = DLFS.connect(root, driveName);
		Path verifyFile = dlfs2.getPath("/docs/readme.txt");
		assertTrue(Files.exists(verifyFile), "File should exist via second connected drive");
		assertEquals("Connected drive!", Files.readString(verifyFile));
	}

	@Test
	public void testForkAndSync() throws Exception {
		// Create a standalone drive with initial content
		DLFSLocal dlfs = DLFS.create();
		Files.createDirectory(dlfs.getPath("/data"));
		Files.writeString(dlfs.getPath("/data/original.txt"), "Original");

		// Fork for isolated batch operations
		DLFSLocal forked = dlfs.fork();

		// Modifications on the fork
		Files.writeString(forked.getPath("/data/batch1.txt"), "Batch file 1");
		Files.writeString(forked.getPath("/data/batch2.txt"), "Batch file 2");

		// Original should NOT see the forked changes yet
		assertFalse(Files.exists(dlfs.getPath("/data/batch1.txt")),
			"Original should not see forked changes before sync");

		// Sync merges changes back via lattice merge
		forked.sync();

		// Original should now see all changes
		assertTrue(Files.exists(dlfs.getPath("/data/batch1.txt")),
			"Original should see changes after sync");
		assertTrue(Files.exists(dlfs.getPath("/data/batch2.txt")),
			"Original should see all synced files");
		assertEquals("Batch file 1", Files.readString(dlfs.getPath("/data/batch1.txt")));

		// Original file should still exist
		assertEquals("Original", Files.readString(dlfs.getPath("/data/original.txt")));
	}

	@Test
	public void testSyncReachesRootCallback() throws Exception {
		// Simulates what NodeServer does: root cursor with onSync callback
		MapLattice<AString, AVector<ACell>> drivesLattice = MapLattice.create(DLFSLattice.INSTANCE);
		RootLatticeCursor<AHashMap<AString, AVector<ACell>>> root =
			(RootLatticeCursor<AHashMap<AString, AVector<ACell>>>) Cursors.createLattice(drivesLattice);

		// Track sync callbacks (like NodeServer's propagator trigger)
		AtomicReference<ACell> synced = new AtomicReference<>();
		root.onSync(value -> { synced.set(value); return value; });

		// Connect a drive and write
		AString driveName = Strings.create("vault");
		DLFSLocal dlfs = DLFS.connect(root, driveName);
		Files.writeString(dlfs.getPath("/test.txt"), "sync me!");

		// Before sync: callback should not have been triggered
		assertNull(synced.get(), "onSync should not fire until sync() is called");

		// Sync from the drive level
		dlfs.sync();

		// After sync: callback should have the full root value including file content
		assertNotNull(synced.get(), "onSync should fire after sync()");

		// Verify content survives a round-trip through the synced value
		AHashMap<AString, AVector<ACell>> syncedMap = (AHashMap<AString, AVector<ACell>>) synced.get();
		DLFSLocal restored = DLFS.connect(Cursors.createLattice(drivesLattice, syncedMap), driveName);
		assertEquals("sync me!", Files.readString(restored.getPath("/test.txt")),
			"File content should survive sync round-trip");
	}

	/**
	 * Exercises a full DLFS file-operation sequence through a Covia-style cursor
	 * chain: {@code root (KeyedLattice :dlfs)} → {@link OwnerLattice} keyed by
	 * {@link AccountKey} → signed value → drives {@link MapLattice} → named drive.
	 *
	 * <p>Verifies that mkdir/write/delete/mkdir/write all flow correctly through
	 * the signing boundary and that a single cached {@link DLFSLocal} survives
	 * intermediate state transitions (delete leaving a tombstone, then a fresh
	 * mkdir at the root).</p>
	 */
	@Test
	public void testCoviaChainSequence() throws Exception {
		AKeyPair kp = AKeyPair.createSeeded(2001);
		AccountKey ak = kp.getAccountKey();
		AString driveName = Strings.create("health-vault");
		Keyword DLFS_KW = Keyword.intern("dlfs");

		OwnerLattice<?> dlfsUsersLattice = OwnerLattice.create(
			MapLattice.create(DLFSLattice.INSTANCE));
		KeyedLattice rootLattice = KeyedLattice.create(DLFS_KW, dlfsUsersLattice);

		LatticeContext ctx = LatticeContext.create(null, kp);
		RootLatticeCursor<Index<Keyword, ACell>> rootCursor =
			Cursors.createLattice(rootLattice, (Index<Keyword, ACell>) Index.EMPTY, ctx);

		ALatticeCursor<?> dlfsCursor = rootCursor.path(DLFS_KW);
		dlfsCursor.withContext(ctx);
		ALatticeCursor<?> userCursor = dlfsCursor.path(ak, Keywords.VALUE);
		DLFSLocal dlfs = DLFS.connect(userCursor, driveName);

		Files.createDirectory(dlfs.getPath("/tmp"));
		Files.writeString(dlfs.getPath("/tmp/deleteme.txt"), "delete me");
		Files.delete(dlfs.getPath("/tmp/deleteme.txt"));
		Files.createDirectory(dlfs.getPath("/lab-results"));
		Files.writeString(dlfs.getPath("/lab-results/panel-q4.json"), "{\"tsh\": 2.8}");

		assertTrue(Files.isDirectory(dlfs.getPath("/lab-results")));
		assertTrue(Files.exists(dlfs.getPath("/lab-results/panel-q4.json")));
		assertEquals("{\"tsh\": 2.8}", Files.readString(dlfs.getPath("/lab-results/panel-q4.json")));

		AHashMap<ACell, SignedData<?>> dlfsSlot =
			(AHashMap<ACell, SignedData<?>>) rootCursor.get().get(DLFS_KW);
		assertNotNull(dlfsSlot, "DLFS slot should exist on root after writes");
		SignedData<?> aliceSigned = dlfsSlot.get(ak);
		assertNotNull(aliceSigned, "Owner's signed slot should exist");
		assertTrue(aliceSigned.checkSignature(), "Signature must be valid");
	}

	/**
	 * Concurrent writes at disjoint top-level paths through a Covia-style
	 * cursor chain. With rsync-like merge semantics, ALL three sibling
	 * directories should survive — none should be lost to merge collision.
	 *
	 * <p>This isolates a bug found in Covia's VaultAdapter where parallel
	 * test methods sharing a cached DLFSLocal lost siblings.</p>
	 */
	@Test
	public void testConcurrentDisjointWrites() throws Exception {
		AKeyPair kp = AKeyPair.createSeeded(2002);
		AccountKey ak = kp.getAccountKey();
		AString driveName = Strings.create("vault");
		Keyword DLFS_KW = Keyword.intern("dlfs");

		OwnerLattice<?> dlfsUsersLattice = OwnerLattice.create(
			MapLattice.create(DLFSLattice.INSTANCE));
		KeyedLattice rootLattice = KeyedLattice.create(DLFS_KW, dlfsUsersLattice);

		LatticeContext ctx = LatticeContext.create(null, kp);
		RootLatticeCursor<Index<Keyword, ACell>> rootCursor =
			Cursors.createLattice(rootLattice, (Index<Keyword, ACell>) Index.EMPTY, ctx);

		ALatticeCursor<?> dlfsCursor = rootCursor.path(DLFS_KW);
		dlfsCursor.withContext(ctx);
		ALatticeCursor<?> userCursor = dlfsCursor.path(ak, Keywords.VALUE);
		final DLFSLocal dlfs = DLFS.connect(userCursor, driveName);

		String[] dirs = { "alpha", "beta", "gamma" };
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(dirs.length);
		java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		for (String d : dirs) {
			new Thread(() -> {
				try {
					start.await();
					Files.createDirectory(dlfs.getPath("/" + d));
				} catch (Throwable t) {
					errors.add(t);
				} finally {
					done.countDown();
				}
			}, "writer-" + d).start();
		}
		start.countDown();
		assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));

		java.util.Set<String> survivors = new java.util.HashSet<>();
		try (java.nio.file.DirectoryStream<Path> s =
				Files.newDirectoryStream(dlfs.getPath("/"))) {
			for (Path p : s) survivors.add(p.getFileName().toString());
		}

		assertTrue(errors.isEmpty(), "writer errors: " + errors);
		assertEquals(java.util.Set.of("alpha", "beta", "gamma"), survivors,
			"all disjoint sibling dirs should survive concurrent writes");
	}

	@Test
	public void testCloneIsSnapshot() throws Exception {
		// Create a drive with content
		DLFSLocal dlfs = DLFS.create();
		Files.createDirectory(dlfs.getPath("/shared"));
		Files.writeString(dlfs.getPath("/shared/file.txt"), "Snapshot test");

		// Clone creates a disconnected snapshot
		DLFSLocal snapshot = dlfs.clone();

		// Modify original
		Files.writeString(dlfs.getPath("/shared/new.txt"), "After snapshot");

		// Snapshot should NOT see the new file
		assertFalse(Files.exists(snapshot.getPath("/shared/new.txt")),
			"Snapshot should be isolated from further changes");

		// Snapshot should still have the original file
		assertEquals("Snapshot test", Files.readString(snapshot.getPath("/shared/file.txt")));
	}
}
