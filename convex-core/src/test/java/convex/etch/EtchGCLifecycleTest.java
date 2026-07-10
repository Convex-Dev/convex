package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

		AString v0 = nonEmbedded(1); // pre-cycle root data
		AVector<ACell> t1 = tree(10); // pre-cycle tree, resolvable only via old file

		// ----- Populate the old file, then reopen for cold caches -----
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(t1, store);
			store.setRootData(v0);
			store.flush();
			store.close();
		}
		EtchStore store = EtchStore.create(f);
		assertFalse(store.isGCInProgress());

		// ----- startGC guards: stale target file must not be adopted or deleted -----
		File stale = new File(f.getCanonicalPath() + "~");
		assertTrue(stale.createNewFile());
		assertThrows(IllegalStateException.class, store::startGC);
		assertTrue(stale.delete());

		// ----- Start the cycle -----
		store.startGC();
		assertTrue(store.isGCInProgress());
		assertThrows(IllegalStateException.class, store::startGC); // double start rejected
		Etch targetEtch = store.getTargetEtch();

		// Root hash carried into the target at cycle start
		assertEquals(v0.getHash(), store.getRootHash());
		assertEquals(v0.getHash(), targetEtch.getRootHash());

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

		// The old file's root is never touched during a cycle
		assertEquals(v0.getHash(), store.getEtch().getRootHash());

		// ----- close() closes both files; idempotent -----
		store.close();
		store.close();
		assertThrows(StoreException.class,
				() -> store.refForHash(nonEmbedded(99).getHash()));
		assertThrows(IOException.class,
				() -> targetEtch.read(nonEmbedded(99).getHash()));

		// ----- Reopen the same files: an abandoned cycle loses nothing -----
		// Old file: exactly the pre-cycle state (root v0, t1 present, v2 absent)
		EtchStore reopened = EtchStore.create(f);
		assertEquals(v0.getHash(), reopened.getRootHash());
		assertEquals(v0, reopened.getRootData());
		assertAllInEtch(reopened.getEtch(), treeHashes(t1), Ref.PERSISTED);
		assertNull(reopened.getEtch().read(v2.getHash()));

		// The abandoned target blocks a new cycle until recovery deals with it
		assertThrows(IllegalStateException.class, reopened::startGC);
		reopened.close();

		// Target file: everything written during the cycle is durably there —
		// exactly what a recovery roll-back (phase 3e) needs
		EtchStore targetStore = EtchStore.create(new File(f.getCanonicalPath() + "~"));
		assertEquals(t3.getHash(), targetStore.getRootHash());
		assertEquals(t3, targetStore.getRootData());
		assertEquals(v2, targetStore.refForHash(v2.getHash()).getValue());
		assertAllInEtch(targetStore.getEtch(), treeHashes(t1), Ref.PERSISTED);
		assertAllInEtch(targetStore.getEtch(), treeHashes(t3), Ref.PERSISTED);
		targetStore.close();
	}
}
