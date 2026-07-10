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
 * These tests use fresh file-backed stores: GC lifecycle is about exact file
 * contents, one of the rare justified exceptions to shared test stores.
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
	 * Recursively collects the hashes of all non-embedded branches below a cell.
	 */
	static void collectBranchHashes(ACell cell, List<Hash> acc) {
		Cells.visitBranchRefs(cell, r -> {
			acc.add(r.getHash());
			collectBranchHashes(r.getValue(), acc);
		});
	}

	/**
	 * Closes a store, registering its GC target file (if any) for deletion.
	 */
	static void cleanup(EtchStore store) {
		Etch t = store.getTargetEtch();
		if (t != null) t.getFile().deleteOnExit();
		store.getEtch().getFile().deleteOnExit();
		store.close();
	}

	@Test
	public void testWritesRedirectToTarget() throws IOException {
		EtchStore store = EtchStore.createTemp("gc3a-writes");
		assertFalse(store.isGCInProgress());
		store.startGC();
		assertTrue(store.isGCInProgress());

		AString v = nonEmbedded(1);
		Cells.persist(v, store);
		Hash h = v.getHash();

		// Written to the target file only; old file untouched
		assertNotNull(store.getTargetEtch().read(h));
		assertNull(store.getEtch().read(h));

		// Readable through the store (target-first read path)
		assertEquals(v, store.refForHash(h).getValue());
		cleanup(store);
	}

	@Test
	public void testReadsFallBackToOldFile() throws IOException {
		File f = File.createTempFile("gc3a-fallback", ".etch");
		f.deleteOnExit();

		AString v = nonEmbedded(11);
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(v, store);
			store.flush();
			store.close();
		}

		EtchStore store = EtchStore.create(f); // cold caches
		store.startGC();

		// Old data resolves via fallback
		Ref<ACell> r = store.refForHash(v.getHash());
		assertNotNull(r);
		assertEquals(v, r.getValue());

		// No copy-on-read: the fallback must not populate the target
		assertNull(store.getTargetEtch().read(v.getHash()));
		cleanup(store);
	}

	/**
	 * The INV-1 core: during a cycle, entries in the old file must not satisfy
	 * a persist — the descent must copy the whole tree into the target.
	 */
	@Test
	public void testPersistDuringCycleCopiesFromOldFile() throws IOException {
		File f = File.createTempFile("gc3a-inv1", ".etch");
		f.deleteOnExit();

		AVector<ACell> t = tree(20);
		Hash rootHash = t.getHash();
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(t, store);
			store.flush();
			store.close();
		}

		EtchStore store = EtchStore.create(f); // cold caches: values resolvable only via old file
		store.startGC();

		// Persist via a lazy root ref: values load from the old file, but the
		// old-file PERSISTED entries must not short-circuit the copy
		Ref<ACell> lazy = RefSoft.createForHash(rootHash, store);
		Ref<ACell> out = store.storeTopRef(lazy, Ref.PERSISTED, null);
		assertTrue(out.getStatus() >= Ref.PERSISTED);

		// Entire tree present in the TARGET file at PERSISTED
		Etch targetEtch = store.getTargetEtch();
		List<Hash> hashes = new ArrayList<>();
		hashes.add(rootHash);
		collectBranchHashes(tree(20), hashes); // equal value, same hashes
		for (Hash h : hashes) {
			RefSoft<?> tr = targetEtch.read(h);
			assertNotNull(tr, "Tree entry not copied to GC target: " + h);
			assertTrue(tr.getStatus() >= Ref.PERSISTED);
		}

		// Repeat persist prunes on target-resident entries: no bytes written
		long len = targetEtch.getDataLength();
		store.storeTopRef(RefSoft.createForHash(rootHash, store), Ref.PERSISTED, null);
		assertEquals(len, targetEtch.getDataLength());
		cleanup(store);
	}

	@Test
	public void testStartGCGuards() throws IOException {
		EtchStore store = EtchStore.createTemp("gc3a-guards");
		store.startGC();
		assertThrows(IllegalStateException.class, store::startGC);
		cleanup(store);

		// A stale target file must not be silently adopted or deleted
		EtchStore store2 = EtchStore.createTemp("gc3a-stale");
		File stale = new File(store2.getFile().getCanonicalPath() + "~");
		assertTrue(stale.createNewFile());
		stale.deleteOnExit();
		assertThrows(IllegalStateException.class, store2::startGC);
		store2.close();
	}

	@Test
	public void testRootDataDuringCycle() throws IOException {
		EtchStore store = EtchStore.createTemp("gc3a-root");

		AString v0 = nonEmbedded(31);
		store.setRootData(v0);
		store.startGC();

		// Root hash carried into the target at cycle start
		assertEquals(v0.getHash(), store.getRootHash());
		assertEquals(v0.getHash(), store.getTargetEtch().getRootHash());

		// A root update during the cycle: hash and full tree land in the target
		AVector<ACell> t = tree(40);
		store.setRootData(t);
		assertEquals(t.getHash(), store.getRootHash());
		assertEquals(t.getHash(), store.getTargetEtch().getRootHash());

		List<Hash> hashes = new ArrayList<>();
		hashes.add(t.getHash());
		collectBranchHashes(t, hashes);
		for (Hash h : hashes) {
			RefSoft<?> tr = store.getTargetEtch().read(h);
			assertNotNull(tr, "Root tree entry not in GC target: " + h);
			assertTrue(tr.getStatus() >= Ref.PERSISTED);
		}

		// The old file's root is never touched during a cycle
		assertEquals(v0.getHash(), store.getEtch().getRootHash());
		cleanup(store);
	}

	@Test
	public void testCloseDuringCycleClosesBoth() throws IOException {
		EtchStore store = EtchStore.createTemp("gc3a-close");
		store.startGC();
		Cells.persist(nonEmbedded(51), store);
		Etch targetEtch = store.getTargetEtch();
		targetEtch.getFile().deleteOnExit();
		store.getEtch().getFile().deleteOnExit();

		store.close();
		store.close(); // idempotent

		// Uncached reads fail cleanly on the closed store...
		assertThrows(StoreException.class,
				() -> store.refForHash(nonEmbedded(52).getHash()));
		// ...and the target file is genuinely closed too
		assertThrows(IOException.class,
				() -> targetEtch.read(nonEmbedded(52).getHash()));
	}
}
