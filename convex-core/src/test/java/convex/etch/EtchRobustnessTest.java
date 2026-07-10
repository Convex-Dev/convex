package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.test.Samples;

/**
 * Robustness tests for Etch store operations, pinning the contracts that
 * store transfer / GC (see docs/ETCH_GC.md) relies on:
 *
 * - monotonic status flags (never downgraded, merged in place)
 * - STORED writes make no subtree claim; PERSISTED guarantees the full tree
 * - persistence of lazily-loaded refs across stores
 * - durability across close/reopen of the same file
 * - clean failure of refs bound to a closed store
 * - thread safety of concurrent writers
 * - recursion depth of persistence over deep structures
 */
public class EtchRobustnessTest {

	/**
	 * Creates a distinct non-embedded string for the given seed.
	 */
	static AString nonEmbedded(int seed) {
		String base = "Etch robustness test value " + seed + ". ";
		return Strings.create(base.repeat(1 + (150 / base.length())));
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

	@Test
	public void testStatusMonotonicity() throws IOException {
		EtchStore store = EtchStore.createTemp();
		AString v = nonEmbedded(1);
		Hash h = v.getHash();

		// Initial write at STORED level
		Cells.store(v, store);
		RefSoft<?> r1 = store.getEtch().read(h);
		assertNotNull(r1);
		assertEquals(Ref.STORED, r1.getStatus());

		// Upgrade to ANNOUNCED merges flags in place
		Cells.announce(v, null, store);
		RefSoft<?> r2 = store.getEtch().read(h);
		assertTrue(r2.getStatus() >= Ref.ANNOUNCED, "Status upgrade not applied");

		// Memory size should be recorded once persisted
		assertTrue(r2.getValue().getMemorySize() > 0);

		// Attempted downgrade via a fresh ref at STORED level must not reduce status
		AString v2 = nonEmbedded(1); // equal value, fresh Ref
		Cells.store(v2, store);
		RefSoft<?> r3 = store.getEtch().read(h);
		assertTrue(r3.getStatus() >= Ref.ANNOUNCED, "Status must never downgrade");
	}

	@Test
	public void testStoredMakesNoSubtreeClaim() throws IOException {
		EtchStore store = EtchStore.createTemp();
		AString child = nonEmbedded(7);
		AVector<ACell> parent = Vectors.of(CVMLong.create(1), child);
		Hash ph = parent.getHash();
		Hash ch = child.getHash();

		// STORED level write covers the top cell only
		Cells.store(parent, store);
		assertNotNull(store.getEtch().read(ph));
		assertNull(store.getEtch().read(ch), "STORED write must not claim children");

		// Upgrading to PERSISTED must bring the child in
		Cells.persist(parent, store);
		RefSoft<?> cr = store.getEtch().read(ch);
		assertNotNull(cr, "PERSISTED write must include children");
		assertTrue(cr.getStatus() >= Ref.PERSISTED);
	}

	@Test
	public void testLazyCrossStorePersist() throws IOException {
		EtchStore a = EtchStore.createTemp();
		EtchStore b = EtchStore.createTemp();

		AVector<ACell> tree = Vectors.of(
				nonEmbedded(21),
				Vectors.of(nonEmbedded(22), Samples.NON_EMBEDDED_BLOB),
				CVMLong.create(42));
		tree = Cells.persist(tree, a);
		Hash rootHash = tree.getHash();

		List<Hash> expected = new ArrayList<>();
		expected.add(rootHash);
		collectBranchHashes(tree, expected);
		assertTrue(expected.size() >= 4);

		// Persist into B starting from a hash-only ref resolved lazily via A.
		// This is the core store-to-store transfer scenario.
		Ref<ACell> lazy = RefSoft.createForHash(rootHash, a);
		Ref<ACell> stored = b.storeTopRef(lazy, Ref.PERSISTED, null);
		assertEquals(rootHash, stored.getHash());
		assertEquals(tree, stored.getValue());

		// Every branch must be in B's file, at full status (bypassing caches)
		for (Hash h : expected) {
			RefSoft<?> r = b.getEtch().read(h);
			assertNotNull(r, "Value missing from destination store: " + h);
			assertTrue(r.getStatus() >= Ref.PERSISTED);
		}
	}

	@Test
	public void testReopenDurability() throws IOException {
		File f = File.createTempFile("etch-reopen", ".etch");
		f.deleteOnExit();

		AVector<ACell> tree = Vectors.of(nonEmbedded(31), Vectors.of(nonEmbedded(32)));
		Hash rootHash;
		List<Hash> branches = new ArrayList<>();
		{
			EtchStore store = EtchStore.create(f);
			tree = Cells.persist(tree, store);
			Cells.announce(tree, null, store);
			store.setRootData(tree);
			rootHash = tree.getHash();
			collectBranchHashes(tree, branches);
			store.flush();
			store.close();
		}

		EtchStore reopened = EtchStore.create(f);
		assertEquals(rootHash, reopened.getRootHash());
		assertEquals(tree, reopened.getRootData());
		for (Hash h : branches) {
			RefSoft<?> r = reopened.getEtch().read(h);
			assertNotNull(r, "Value lost across reopen: " + h);
			assertTrue(r.getStatus() >= Ref.ANNOUNCED, "Flags lost across reopen: " + h);
		}

		EtchUtils.FullValidator vd = EtchUtils.getFullValidator();
		reopened.getEtch().visitIndex(vd);
		assertTrue(vd.values >= branches.size());
		reopened.close();
	}

	@Test
	public void testClosedStoreContract() throws IOException {
		File f = File.createTempFile("etch-close", ".etch");
		f.deleteOnExit();

		AString v1 = nonEmbedded(41);
		AString v2 = nonEmbedded(42);
		{
			EtchStore store = EtchStore.create(f);
			Cells.persist(v1, store);
			Cells.persist(v2, store);
			store.flush();
			store.close();
		}

		// Fresh store instance with cold caches
		EtchStore reopened = EtchStore.create(f);
		Ref<ACell> lazy1 = RefSoft.createForHash(v1.getHash(), reopened);
		assertEquals(v1, lazy1.getValue());
		reopened.close();

		// After close: uncached lookups fail cleanly, refs report missing data
		assertNull(reopened.refForHash(v2.getHash()));
		Ref<ACell> lazy2 = RefSoft.createForHash(v2.getHash(), reopened);
		assertTrue(lazy2.isMissing());
		assertThrows(MissingDataException.class, lazy2::getValue);
	}

	@Test
	public void testConcurrentWriters() throws Exception {
		EtchStore store = EtchStore.createTemp();
		int NTHREADS = 8;
		int PER = 100;

		List<Callable<Hash>> tasks = new ArrayList<>();
		for (int t = 0; t < NTHREADS; t++) {
			for (int i = 0; i < PER; i++) {
				final int seed = 1000 + t * PER + i;
				tasks.add(() -> {
					AVector<ACell> v = Vectors.of(CVMLong.create(seed), nonEmbedded(seed));
					Ref<ACell> r = store.storeTopRef(v.getRef(), Ref.PERSISTED, null);
					return r.getHash();
				});
			}
		}

		ExecutorService ex = Executors.newFixedThreadPool(NTHREADS);
		try {
			List<Future<Hash>> results = ex.invokeAll(tasks);
			for (Future<Hash> fr : results) {
				Hash h = fr.get(); // propagates any worker exception
				assertNotNull(store.refForHash(h));
			}
		} finally {
			ex.shutdown();
		}

		// Structural integrity after concurrent writes
		EtchUtils.FullValidator vd = EtchUtils.getFullValidator();
		store.getEtch().visitIndex(vd);
		assertTrue(vd.values >= NTHREADS * PER);
	}

	@Test
	public void testDeepStructurePersist() throws IOException {
		EtchStore store = EtchStore.createTemp();

		// Characterises the current recursion depth tolerance of storeRef.
		// Scale up once persistence uses an iterative descent (see ETCH_GC.md).
		int DEPTH = 400;
		ACell v = CVMLong.create(0x1234);
		for (int i = 0; i < DEPTH; i++) {
			v = Vectors.of(v);
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> persisted = (AVector<ACell>) Cells.persist(v, store);
		Hash h = persisted.getHash();

		Ref<ACell> r = store.refForHash(h);
		assertNotNull(r);

		// Walk back down iteratively to the leaf
		ACell cur = r.getValue();
		for (int i = 0; i < DEPTH; i++) {
			assertTrue(cur instanceof AVector, "Expected vector at depth " + i);
			cur = ((AVector<?>) cur).getRef(0).getValue();
		}
		assertEquals(CVMLong.create(0x1234), cur);
	}
}
