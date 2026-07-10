package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.test.Samples;

/**
 * Parameterised store contract tests that run against both MemoryStore and EtchStore.
 */
public class ParamTestStores {

	/**
	 * Shared store instances: tests use distinct values, so instances are safely
	 * reused across all parameterised tests.
	 */
	private static final MemoryStore SHARED_MEMORY = new MemoryStore();

	static Stream<AStore> storeProvider() {
		return Stream.of(SHARED_MEMORY, Samples.TEST_STORE);
	}

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testStoreTopRefStoredStatus(AStore store) throws IOException {
		AVector<CVMLong> v = Vectors.of(1L, 2L, 3L);
		Ref<AVector<CVMLong>> ref = v.getRef();
		Ref<AVector<CVMLong>> stored = store.storeTopRef(ref, Ref.STORED, null);
		assertTrue(stored.getStatus() >= Ref.STORED);

		// Should be retrievable by hash
		Ref<?> found = store.refForHash(stored.getHash());
		assertNotNull(found);
		assertTrue(found.getStatus() >= Ref.STORED);
	}

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testStoreTopRefPersistedStatus(AStore store) throws IOException {
		AVector<CVMLong> v = Vectors.of(4L, 5L, 6L);
		Ref<AVector<CVMLong>> ref = v.getRef();
		Ref<AVector<CVMLong>> persisted = store.storeTopRef(ref, Ref.PERSISTED, null);
		assertTrue(persisted.getStatus() >= Ref.PERSISTED);

		Ref<?> found = store.refForHash(persisted.getHash());
		assertNotNull(found);
		assertTrue(found.getStatus() >= Ref.PERSISTED);
	}

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testNestedDescendantsRetrievable(AStore store) throws IOException {
		// Build a non-trivial nested structure
		AVector<CVMLong> inner = Vectors.of(10L, 20L, 30L, 40L, 50L);
		AVector<ACell> outer = Vectors.of(inner, inner, CVMLong.create(99));

		AVector<ACell> persisted = Cells.persist(outer, store);
		Hash outerHash = Cells.getHash(persisted);
		Hash innerHash = Cells.getHash(inner);

		// Both parent and child should be retrievable
		assertNotNull(store.refForHash(outerHash));
		if (!inner.isEmbedded()) {
			Ref<?> innerRef = store.refForHash(innerHash);
			assertNotNull(innerRef, "Child cell should be individually retrievable after persist");
			assertTrue(innerRef.getStatus() >= Ref.PERSISTED);
		}
	}

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testRefForHashUnknown(AStore store) {
		// A random hash should not be in any fresh store
		Hash unknown = Hash.fromHex("aaaa000011110000aaaa000011110000aaaa000011110000aaaa000011110000");
		assertEquals(null, store.refForHash(unknown));
	}

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testRootDataRoundTrip(AStore store) throws IOException {
		AVector<ACell> v = Vectors.of(CVMLong.create(1), Samples.NON_EMBEDDED_STRING);
		store.setRootData(v);
		assertEquals(v, store.getRootData());
		assertEquals(Cells.getHash(v), store.getRootHash());

		// Root can be reset to nil
		store.setRootData(null);
		assertNull(store.getRootData());
	}

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testPersistedSubtreeContract(AStore store) throws IOException {
		// Branchy structure with non-embedded values at multiple depths.
		// PERSISTED must guarantee every non-embedded descendant is individually
		// retrievable at >= PERSISTED (the "full tree is stored" contract).
		AVector<ACell> tree = Vectors.of(
				Samples.NON_EMBEDDED_STRING,
				Vectors.of(CVMLong.create(1), Samples.NON_EMBEDDED_BLOB),
				Vectors.of(Vectors.of(Samples.MIN_TREE_STRING)));

		AVector<ACell> persisted = Cells.persist(tree, store);

		List<Hash> branches = new ArrayList<>();
		collectBranchHashes(persisted, branches);
		assertFalse(branches.isEmpty());

		for (Hash h : branches) {
			Ref<?> found = store.refForHash(h);
			assertNotNull(found, "Branch missing after persist: " + h);
			assertTrue(found.getStatus() >= Ref.PERSISTED,
					"Branch below PERSISTED after persist: " + h + " status=" + found.getStatus());
		}
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

	@ParameterizedTest(autoCloseArguments = false)
	@MethodSource("storeProvider")
	public void testLargeSetPersist(AStore store) throws IOException {
		// Build a set large enough to have tree branches (non-embedded children)
		ASet<ACell> set = (ASet<ACell>) Sets.empty();
		for (int i = 0; i < 500; i++) {
			set = (ASet<ACell>) set.conj(CVMLong.create(i));
		}
		set = Cells.persist(set, store);
		Hash h = Cells.getHash(set);
		Ref<?> ref = store.refForHash(h);
		assertNotNull(ref);
		assertTrue(ref.getStatus() >= Ref.PERSISTED);
		assertEquals(500L, set.count());
	}
}
