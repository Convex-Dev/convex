package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.exceptions.StoreException;

/**
 * Tests for the GC cycle split-store plumbing in EtchStore (phase 3a of
 * convex-core/docs/ETCH_GC.md): write redirection, read fallback, and the
 * target-only persistence check that establishes INV-1 during a live cycle.
 *
 * One long lifecycle walk over a single file-backed store (GC lifecycle is
 * about exact file contents, so it cannot use the shared test stores — but it
 * needs only this one file plus its GC target).
 */
public class EtchGCLifecycleTest {

	/**
	 * Creates a distinct non-embedded string for the given seed.
	 */
	static AString nonEmbedded(int seed) {
		String base = "Etch GC lifecycle test value " + seed + ". ";
		return Strings.create(base.repeat(1 + (150 / base.length())));
	}

	/**
	 * Tree with a non-embedded root (so the root is its own store entry) and
	 * non-embedded children.
	 */
	static AVector<ACell> tree(int seed) {
		return Vectors.of(
				nonEmbedded(seed), nonEmbedded(seed + 1), nonEmbedded(seed + 2),
				nonEmbedded(seed + 3), nonEmbedded(seed + 4));
	}

	/**
	 * Collects the root hash and the hashes of all non-embedded branches.
	 */
	static List<Hash> treeHashes(ACell root) {
		List<Hash> acc = new ArrayList<>();
		acc.add(root.getHash());
		collectBranchHashes(root, acc);
		return acc;
	}

	static void collectBranchHashes(ACell cell, List<Hash> acc) {
		Cells.visitBranchRefs(cell, r -> {
			acc.add(r.getHash());
			collectBranchHashes(r.getValue(), acc);
		});
	}

	static void assertAllInEtch(Etch e, List<Hash> hashes, int minStatus) throws IOException {
		for (Hash h : hashes) {
			RefSoft<?> r = e.read(h);
			assertNotNull(r, "Entry missing: " + h);
			assertTrue(r.getStatus() >= minStatus, "Status too low for: " + h);
		}
	}

	@Test
	public void testGCCycleLifecycle() throws IOException {
		File f = File.createTempFile("gc3a-lifecycle", ".etch");
		f.deleteOnExit();
		new File(f.getCanonicalPath() + "~").deleteOnExit();

		AVector<ACell> t0 = tree(1); // pre-cycle root tree, ANNOUNCED in the old file
		AVector<ACell> t1 = tree(10); // pre-cycle tree, resolvable only via old file

		// ----- Populate the old file, then reopen for cold caches -----
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(t1, store);
			store.setRootData(t0);
			Cells.announce(t0, null, store); // sweep must preserve ANNOUNCED
			store.flush();
			store.close();
		}
		EtchStore store = EtchStore.create(f);
		assertFalse(store.isGCInProgress());
		assertFalse(store.isGCComplete());
		assertThrows(IllegalStateException.class, store::transferGC); // no cycle
		assertThrows(IllegalStateException.class, store::verifyGC);

		// ----- A stale target file is neither adopted nor deleted: the cycle
		// starts on the next generational name, preserving it for recovery -----
		File stale = new File(f.getCanonicalPath() + "~");
		assertTrue(stale.createNewFile());
		stale.deleteOnExit();

		// ----- Start the cycle -----
		store.startGC();
		assertTrue(store.isGCInProgress());
		assertThrows(IllegalStateException.class, store::startGC); // double start rejected
		Etch targetEtch = store.getTargetEtch();
		targetEtch.getFile().deleteOnExit();
		assertNotEquals(stale.getCanonicalPath(), targetEtch.getFile().getCanonicalPath());
		assertEquals(0L, stale.length()); // stale file untouched

		// Root hash carried into the target at cycle start
		assertEquals(t0.getHash(), store.getRootHash());
		assertEquals(t0.getHash(), targetEtch.getRootHash());

		// ----- Read fallback: old data resolves (caches are cold), no copy-on-read -----
		Ref<ACell> r1 = store.refForHash(t1.getHash());
		assertNotNull(r1);
		assertEquals(t1, r1.getValue());
		assertNull(targetEtch.read(t1.getHash()), "Read fallback must not populate the target");

		// ----- Write redirection: new data lands in the target only -----
		AString v2 = nonEmbedded(20);
		Cells.persist(v2, store);
		assertNotNull(targetEtch.read(v2.getHash()));
		assertNull(store.getEtch().read(v2.getHash()), "Old file must not receive writes");
		assertEquals(v2, store.refForHash(v2.getHash()).getValue());

		// ----- Transfer sweep: root tree is only in the old file, so the cycle
		// is incomplete until the sweep copies it across -----
		assertFalse(store.isGCComplete());
		assertTrue(store.verifyGC().contains(t0.getHash()));

		store.transferGC();
		assertTrue(store.isGCComplete());
		assertTrue(store.verifyGC().isEmpty());
		// Status preserved from the old file: ANNOUNCED, not merely PERSISTED
		assertAllInEtch(targetEtch, treeHashes(t0), Ref.ANNOUNCED);

		// ----- INV-1 core: old-file entries must not satisfy a persist; the
		// descent must copy the whole tree into the target -----
		Ref<ACell> out = store.storeTopRef(RefSoft.createForHash(t1.getHash(), store), Ref.PERSISTED, null);
		assertTrue(out.getStatus() >= Ref.PERSISTED);
		assertAllInEtch(targetEtch, treeHashes(t1), Ref.PERSISTED);

		// Repeat persist prunes on target-resident entries: no bytes written
		long len = targetEtch.getDataLength();
		store.storeTopRef(RefSoft.createForHash(t1.getHash(), store), Ref.PERSISTED, null);
		assertEquals(len, targetEtch.getDataLength());

		// ----- Root update during the cycle: hash and full tree land in the target -----
		AVector<ACell> t3 = tree(30);
		store.setRootData(t3);
		assertEquals(t3.getHash(), store.getRootHash());
		assertEquals(t3.getHash(), targetEtch.getRootHash());
		assertAllInEtch(targetEtch, treeHashes(t3), Ref.PERSISTED);

		// Completion is STICKY: the root update landed its full tree in the
		// target via the cycle write path, so the earlier sweep still counts
		assertTrue(store.isGCComplete());
		assertTrue(store.verifyGC().isEmpty());

		// The old file's root is never touched during a cycle
		assertEquals(t0.getHash(), store.getEtch().getRootHash());

		// ----- close() closes both files; idempotent -----
		store.close();
		store.close();
		assertThrows(StoreException.class,
				() -> store.refForHash(nonEmbedded(99).getHash()));
		assertThrows(IOException.class,
				() -> targetEtch.read(nonEmbedded(99).getHash()));

		// ----- Reopen via EtchStore.create: automatic recovery (phase 3e)
		// rolls the abandoned cycle forward - nothing written during it is
		// lost, and the root advances to the cycle's last root -----
		assertTrue(targetEtch.getFile().exists()); // cycle data durably on disk
		EtchStore reopened = EtchStore.create(f);
		assertEquals(t3.getHash(), reopened.getRootHash());
		assertEquals(t3, reopened.getRootData());
		assertNotNull(reopened.getEtch().read(v2.getHash())); // cycle novelty recovered
		assertAllInEtch(reopened.getEtch(), treeHashes(t3), Ref.PERSISTED);
		assertAllInEtch(reopened.getEtch(), treeHashes(t1), Ref.PERSISTED); // pre-cycle data intact
		assertAllInEtch(reopened.getEtch(), treeHashes(t0), Ref.ANNOUNCED);
		reopened.close();

		// Recovery is idempotent: a second open is clean (any target files
		// still pinned by this process are re-rolled-back harmlessly)
		EtchStore again = EtchStore.create(f);
		assertEquals(t3.getHash(), again.getRootHash());
		again.close();
	}

	/**
	 * The cancel path: everything written during a cycle (novelty, root
	 * updates, STORED-only entries) survives cancellation in the old file,
	 * the store remains fully operational, and the result is durable.
	 */
	@Test
	public void testGCCancelLifecycle() throws IOException {
		File f = File.createTempFile("gc3b-cancel", ".etch");
		f.deleteOnExit();

		AString v0 = nonEmbedded(101);
		AVector<ACell> t1 = tree(110);

		// ----- Populate the old file, then reopen for cold caches -----
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(t1, store);
			store.setRootData(v0);
			store.flush();
			store.close();
		}
		EtchStore store = EtchStore.create(f);
		assertThrows(IllegalStateException.class, store::cancelGC); // no cycle to cancel

		store.startGC();
		Etch targetEtch = store.getTargetEtch();
		targetEtch.getFile().deleteOnExit(); // cancel's delete may fail while mapped

		// ----- Cycle activity: novelty, a root update, a STORED-only entry -----
		AString v2 = nonEmbedded(120);
		Ref<ACell> v2ref = Cells.persist(v2, store).getRef();
		AVector<ACell> t3 = tree(130);
		store.setRootData(t3);
		AVector<ACell> storedOnly = tree(140);
		store.storeTopRef(storedOnly.getRef(), Ref.STORED, null);
		assertNull(store.getEtch().read(v2.getHash())); // target-only so far

		// A completed sweep does not survive cancellation
		store.transferGC();
		assertTrue(store.isGCComplete());

		// ----- Cancel: reverse migration back into the old file -----
		store.cancelGC();
		assertFalse(store.isGCInProgress());
		assertFalse(store.isGCComplete());
		assertNull(store.getTargetEtch());
		assertThrows(IllegalStateException.class, store::cancelGC); // cycle is over

		Etch old = store.getEtch();
		// Novelty and root tree fully in the old file at earned status
		assertTrue(old.read(v2.getHash()).getStatus() >= Ref.PERSISTED);
		assertAllInEtch(old, treeHashes(t3), Ref.PERSISTED);
		// STORED-only entry migrated at exactly STORED: no subtree claim, and
		// its children (never written anywhere) stay absent
		RefSoft<?> so = old.read(storedOnly.getHash());
		assertNotNull(so);
		assertEquals(Ref.STORED, so.getStatus());
		assertNull(old.read(nonEmbedded(140).getHash()));
		// Root hash carried back
		assertEquals(t3.getHash(), store.getRootHash());
		assertEquals(t3.getHash(), old.getRootHash());
		// Refs issued during the cycle still resolve
		assertEquals(v2, v2ref.getValue());

		// ----- Store fully operational: writes land in the old file again -----
		AString v4 = nonEmbedded(150);
		Cells.persist(v4, store);
		assertNotNull(old.read(v4.getHash()));

		// ----- A new cycle starts even if the cancelled target file is pinned
		// (generational naming). Cancel races live persists: writers before the
		// flip are drained and their values reverse-migrated; writers after go
		// straight to the old file. Nothing may be lost -----
		store.startGC();
		store.getTargetEtch().getFile().deleteOnExit();
		int NT = 4, PER = 50;
		ExecutorService ex = Executors.newFixedThreadPool(NT);
		List<Future<?>> futures = new ArrayList<>();
		for (int w = 0; w < NT; w++) {
			final int base = 200 + w * PER;
			futures.add(ex.submit(() -> {
				for (int i = 0; i < PER; i++) {
					Cells.persist(nonEmbedded(base + i), store);
				}
				return null;
			}));
		}
		store.cancelGC(); // concurrent with the writers
		try {
			for (Future<?> fut : futures) {
				fut.get(); // propagate any writer failure
			}
		} catch (Exception e) {
			throw new IOException("Writer failed during concurrent cancel", e);
		} finally {
			ex.shutdown();
		}
		assertFalse(store.isGCInProgress());
		for (int i = 200; i < 200 + NT * PER; i++) {
			assertNotNull(old.read(nonEmbedded(i).getHash()),
					"Value lost during concurrent cancel: " + i);
		}
		assertEquals(t3.getHash(), store.getRootHash());
		store.close();

		// ----- Reopen: the cancelled state is durable -----
		EtchStore reopened = EtchStore.create(f);
		assertEquals(t3.getHash(), reopened.getRootHash());
		assertEquals(t3, reopened.getRootData());
		assertNotNull(reopened.getEtch().read(v2.getHash()));
		assertAllInEtch(reopened.getEtch(), treeHashes(t1), Ref.PERSISTED);
		reopened.close();
	}

	/**
	 * The completion path: cutover to a successor store; the old store remains
	 * a fully functional view until the caller closes it; garbage is actually
	 * collected.
	 */
	@Test
	public void testGCCompleteLifecycle() throws IOException {
		File f = File.createTempFile("gc3d-complete", ".etch");
		f.deleteOnExit();

		AVector<ACell> t1 = tree(210); // will become garbage
		AVector<ACell> t0 = tree(201); // pre-cycle root, superseded during the cycle
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(t1, store);
			store.setRootData(t0);
			store.flush();
			store.close();
		}
		EtchStore store = EtchStore.create(f);
		store.startGC();
		Etch targetEtch = store.getTargetEtch();
		targetEtch.getFile().deleteOnExit();

		// Cutover is hard-gated on a completed sweep (no force override)
		assertThrows(IllegalStateException.class, store::completeGC);

		// Cycle activity, then a root update superseding t0
		AString v2 = nonEmbedded(220);
		Cells.persist(v2, store);
		AVector<ACell> t3 = tree(230);
		store.setRootData(t3);

		store.transferGC();
		assertTrue(store.isGCComplete());

		// ----- Cutover -----
		EtchStore newStore = store.completeGC();
		assertFalse(store.isGCInProgress());
		assertFalse(store.isGCComplete());
		assertThrows(IllegalStateException.class, store::completeGC); // no second successor
		assertThrows(IllegalStateException.class, store::cancelGC);   // successor's file is live
		assertThrows(IllegalStateException.class, store::transferGC);
		assertThrows(IllegalStateException.class, store::startGC);

		// Successor runs on the target file with the cycle's final state
		assertSame(targetEtch, newStore.getEtch());
		assertEquals(t3.getHash(), newStore.getRootHash());
		assertEquals(t3, newStore.getRootData());
		assertEquals(v2, newStore.refForHash(v2.getHash()).getValue());

		// GARBAGE IS COLLECTED: t1 was unreachable from the final root
		assertNull(newStore.refForHash(t1.getHash()));

		// Old store remains a full view: migrated data via the target file,
		// garbage still readable via the old file
		assertEquals(v2, store.refForHash(v2.getHash()).getValue());
		assertEquals(t1, store.refForHash(t1.getHash()).getValue());

		// Old store still accepts writes: they land in the successor's file
		AString v4 = nonEmbedded(240);
		Cells.persist(v4, store);
		assertEquals(v4, newStore.refForHash(v4.getHash()).getValue());
		assertNull(store.getEtch().read(v4.getHash())); // not in the old file

		// Completion marker names the generational target file
		File marker = new File(f.getCanonicalPath() + ".gc-complete");
		marker.deleteOnExit();
		assertTrue(marker.exists());
		assertTrue(java.nio.file.Files.readString(marker.toPath())
				.contains(targetEtch.getFile().getName()));

		// ----- Caller decides retirement: closing the old store must not
		// touch the successor -----
		store.close();
		AString v5 = nonEmbedded(250);
		Cells.persist(v5, newStore);
		assertEquals(v5, newStore.refForHash(v5.getHash()).getValue());
		assertNull(newStore.refForHash(t1.getHash())); // garbage now gone for good

		// ----- Second GC on the successor: cutovers chain via markers, and
		// target names stay bounded off the BASE file (f~, f~1 - never f~~) -----
		newStore.startGC();
		Etch target2 = newStore.getTargetEtch();
		target2.getFile().deleteOnExit();
		assertEquals(f.getCanonicalPath() + "~1", target2.getFile().getCanonicalPath());
		newStore.transferGC();
		EtchStore finalStore = newStore.completeGC();
		AString v6 = nonEmbedded(260);
		Cells.persist(v6, finalStore);
		newStore.close();
		finalStore.close();
		new File(f.getCanonicalPath() + "~.gc-complete").deleteOnExit();

		// ----- Restart via EtchStore.create(f): recovery (phase 3e) follows
		// the marker chain f -> f~ -> f~1 and adopts (or defers to) the tail -----
		EtchStore adopted = EtchStore.create(f);
		assertEquals(t3.getHash(), adopted.getRootHash());
		assertNotNull(adopted.getEtch().read(v6.getHash())); // post-second-cutover data retained
		assertNull(adopted.getEtch().read(t1.getHash()));    // garbage stays collected
		assertNull(adopted.getEtch().read(v4.getHash()));    // never root-reachable: collected by cycle 2
		assertNull(adopted.getEtch().read(v5.getHash()));    // ditto
		assertAllInEtch(adopted.getEtch(), treeHashes(t3), Ref.PERSISTED);
		// The logical base survives regardless of which physical file is open
		assertEquals(f.getCanonicalFile(), adopted.getBaseFile().getCanonicalFile());

		File adoptedFile = adopted.getFile().getCanonicalFile();
		if (adoptedFile.equals(f.getCanonicalFile())) {
			// Full adoption: marker consumed; superseded files are DELETED, not
			// archived - the deletion is the disk reclamation
			assertFalse(marker.exists());
		} else {
			// Deferred adoption (files pinned by this same process, e.g. on
			// Windows): running on the chain tail with the correct data; the
			// renames are retried on the next start via the marker breadcrumb
			assertEquals(target2.getFile().getCanonicalFile(), adoptedFile);
			assertTrue(marker.exists());
		}
		adopted.close();
	}
}
