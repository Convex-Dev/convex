package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Disabled;
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

/**
 * Regression tests for the store status integrity invariant:
 *
 *   RECORDED STATUS MUST BE IMMEDIATELY PROVABLE FOR THE GIVEN STORE.
 *
 * A store must never record (in its files), return, or cache status or store
 * bindings that it cannot prove from the operation actually performed:
 *
 * - a STORED-level write proves only the top entry: recorded status is STORED,
 *   regardless of status carried by the incoming Ref (which may be forged, or
 *   earned in a different store)
 * - a PERSISTED-level write proves the subtree only because children are
 *   descended first
 * - refs returned or cached by a store must be bound to that store, never to a
 *   foreign store
 * - status already earned by THIS store is never downgraded (monotonic merge
 *   with the store's own entry flags)
 *
 * See convex-core/docs/ETCH_GC.md "Ref status invariants". The failing tests
 * here prove the current defects; they must pass after the write-boundary fix.
 */
public class EtchStatusIntegrityTest {

	/**
	 * Creates a distinct non-embedded string for the given seed.
	 */
	static AString nonEmbedded(int seed) {
		String base = "Etch status integrity test value " + seed + ". ";
		return Strings.create(base.repeat(1 + (150 / base.length())));
	}

	// -----------------------------------------------------------------
	// Regression tests: prove current defects, must pass after the fix
	// -----------------------------------------------------------------

	/**
	 * Single-store: a Ref carrying forged/unproven PERSISTED status, written at
	 * STORED level, must be recorded at STORED — the store proved nothing more.
	 */
	@Test
	public void testForgedStatusNotRecorded() throws IOException {
		EtchStore store = EtchStore.createTemp();

		AVector<ACell> parent = Vectors.of(nonEmbedded(101), CVMLong.create(1));
		Hash ph = parent.getHash();
		Hash ch = parent.getRef(0).getHash();

		// Forge PERSISTED status on an unpersisted ref (public but dangerous API)
		Ref<ACell> forged = parent.getRef().withMinimumStatus(Ref.PERSISTED);

		store.storeTopRef(forged, Ref.STORED, null);

		// Nothing descended: child must be absent
		assertNull(store.getEtch().read(ch));

		// The file must record only what was proven: STORED
		RefSoft<?> r = store.getEtch().read(ph);
		assertNotNull(r);
		assertEquals(Ref.STORED, r.getStatus(),
				"Recorded status must be what the write proved, not what the Ref claimed");
	}

	/**
	 * Cross-store: status earned in store A is not provable in store B. A
	 * STORED-level write into B must record (and return) STORED.
	 */
	@Test
	public void testForeignStatusCappedAtStoredLevel() throws IOException {
		EtchStore a = EtchStore.createTemp();
		EtchStore b = EtchStore.createTemp();

		// Non-embedded parent with non-embedded children, fully persisted in A
		AVector<ACell> parent = Vectors.of(
				nonEmbedded(111), nonEmbedded(112), nonEmbedded(113),
				nonEmbedded(114), nonEmbedded(115));
		parent = Cells.persist(parent, a);
		Hash ph = parent.getHash();
		Hash ch = parent.getRef(0).getHash();

		Ref<ACell> fromA = parent.getRef(); // carries PERSISTED earned in A
		assertTrue(fromA.getStatus() >= Ref.PERSISTED);

		Ref<ACell> stored = b.storeTopRef(fromA, Ref.STORED, null);

		// Children were not descended into B
		assertNull(b.getEtch().read(ch));

		// B's file must record STORED only
		RefSoft<?> r = b.getEtch().read(ph);
		assertNotNull(r);
		assertEquals(Ref.STORED, r.getStatus(),
				"Foreign status must not be recorded in the destination file");

		// The returned ref's claim must match what B proved
		assertEquals(Ref.STORED, stored.getStatus(),
				"Returned ref must not carry unproven status for this store");
	}

	/**
	 * A PERSISTED-level write earns exactly PERSISTED: the descent persists
	 * children, it does not announce them. A ref carrying ANNOUNCED (earned by a
	 * peer against another store) must not have ANNOUNCED recorded here — a
	 * false ANNOUNCED claim makes Cells.announce skip the value in future
	 * novelty broadcasts.
	 */
	@Test
	public void testAnnouncedNotAdoptedThroughPersist() throws IOException {
		EtchStore a = EtchStore.createTemp();
		EtchStore b = EtchStore.createTemp();

		// Non-embedded parent with a non-embedded child, ANNOUNCED in A
		AVector<ACell> parent = Vectors.of(
				nonEmbedded(171), nonEmbedded(172), nonEmbedded(173),
				nonEmbedded(174), nonEmbedded(175));
		parent = Cells.announce(parent, null, a);
		Hash ph = parent.getHash();
		Hash ch = parent.getRef(0).getHash();

		Ref<ACell> fromA = parent.getRef();
		assertTrue(fromA.getStatus() >= Ref.ANNOUNCED);

		// Genuine persist into B: earns PERSISTED, nothing more
		b.storeTopRef(fromA, Ref.PERSISTED, null);

		RefSoft<?> pr = b.getEtch().read(ph);
		RefSoft<?> cr = b.getEtch().read(ch);
		assertNotNull(pr);
		assertNotNull(cr, "Persist must include children");
		assertEquals(Ref.PERSISTED, pr.getStatus(),
				"Persist earns PERSISTED exactly; ANNOUNCED was never earned here");
		assertEquals(Ref.PERSISTED, cr.getStatus(),
				"Children must not inherit foreign ANNOUNCED status either");
	}

	/**
	 * Refs returned and cached by a store must be bound to that store. Currently
	 * an embedded top-level cell skips the rebind, leaking (and caching) a ref
	 * bound to the source store.
	 */
	@Disabled("V1 outstanding: foreign-store ref binding on embedded top-level writes — see ETCH_GC.md Ref status invariants")
	@Test
	public void testForeignRefsRebindOnPersist() throws IOException {
		EtchStore a = EtchStore.createTemp();
		EtchStore b = EtchStore.createTemp();

		// Embedded top-level cell with a non-embedded child
		AVector<ACell> tree = Vectors.of(nonEmbedded(121), CVMLong.create(42));
		tree = Cells.persist(tree, a);
		Hash rootHash = tree.getHash();

		// Full persist into B: PERSISTED is earned (children descended)
		Ref<ACell> lazy = RefSoft.createForHash(rootHash, a);
		Ref<ACell> stored = b.storeTopRef(lazy, Ref.PERSISTED, null);

		// Returned ref must not be bound to a foreign store
		if (stored instanceof RefSoft) {
			assertSame(b, ((RefSoft<?>) stored).getStore(),
					"Returned ref bound to a foreign store");
		}

		// B's cache must serve only B-bound refs
		Ref<ACell> fromB = b.refForHash(rootHash);
		assertNotNull(fromB);
		if (fromB instanceof RefSoft) {
			assertSame(b, ((RefSoft<?>) fromB).getStore(),
					"Store cache poisoned with a foreign-store ref");
		}
	}

	/**
	 * Sub-STORED requests perform no write, only caching. The cache must still
	 * never be populated with a ref bound to a foreign store.
	 */
	@Disabled("V1 outstanding: foreign-store refs cached by sub-STORED requests — see ETCH_GC.md Ref status invariants")
	@Test
	public void testSubStoredRequestDoesNotCacheForeignRefs() throws IOException {
		EtchStore a = EtchStore.createTemp();
		EtchStore b = EtchStore.createTemp();

		AString v = nonEmbedded(131);
		Cells.persist(v, a);
		Hash h = v.getHash();
		Ref<ACell> fromA = v.getRef();
		assertTrue(fromA instanceof RefSoft);
		assertSame(a, ((RefSoft<?>) fromA).getStore());

		// Cache-only request with a foreign-bound ref
		b.storeTopRef(fromA, Ref.UNKNOWN, null);

		// B may or may not serve the value, but must never serve an A-bound ref
		Ref<ACell> fromB = b.refForHash(h);
		if (fromB instanceof RefSoft) {
			assertSame(b, ((RefSoft<?>) fromB).getStore(),
					"Store cache poisoned with a foreign-store ref");
		}
	}

	// -----------------------------------------------------------------
	// Positive controls: pass today, must STILL pass after the fix.
	// Guard against the cap being applied too aggressively.
	// -----------------------------------------------------------------

	/**
	 * A genuine PERSISTED-level write earns PERSISTED: children are descended
	 * and written first, so recording it is provable.
	 */
	@Test
	public void testEarnedPersistRecordsPersisted() throws IOException {
		EtchStore store = EtchStore.createTemp();

		AVector<ACell> parent = Vectors.of(nonEmbedded(141), CVMLong.create(1));
		Hash ph = parent.getHash();
		Hash ch = parent.getRef(0).getHash();

		Cells.persist(parent, store);

		RefSoft<?> pr = store.getEtch().read(ph);
		RefSoft<?> cr = store.getEtch().read(ch);
		assertNotNull(pr);
		assertNotNull(cr);
		assertTrue(pr.getStatus() >= Ref.PERSISTED);
		assertTrue(cr.getStatus() >= Ref.PERSISTED);
	}

	/**
	 * Upgrading a STORED entry by a genuine persist records the higher level:
	 * the upgrade descent makes it provable.
	 */
	@Test
	public void testUpgradePathRecordsEarnedStatus() throws IOException {
		EtchStore store = EtchStore.createTemp();

		AVector<ACell> parent = Vectors.of(nonEmbedded(151), CVMLong.create(1));
		Hash ph = parent.getHash();
		Hash ch = parent.getRef(0).getHash();

		Cells.store(parent, store); // STORED: top entry only
		assertNull(store.getEtch().read(ch));

		Cells.persist(parent, store); // genuine upgrade

		RefSoft<?> pr = store.getEtch().read(ph);
		RefSoft<?> cr = store.getEtch().read(ch);
		assertNotNull(cr, "Upgrade must bring children in");
		assertTrue(pr.getStatus() >= Ref.PERSISTED);
	}

	/**
	 * Status already earned by this store is never downgraded: a later
	 * STORED-level touch of an ANNOUNCED entry leaves ANNOUNCED intact.
	 */
	@Test
	public void testEarnedStatusNeverDowngraded() throws IOException {
		EtchStore store = EtchStore.createTemp();

		AString v = nonEmbedded(161);
		Hash h = v.getHash();

		Cells.announce(v, null, store);
		assertTrue(store.getEtch().read(h).getStatus() >= Ref.ANNOUNCED);

		// Fresh equal value, low-status re-store
		AString v2 = nonEmbedded(161);
		Cells.store(v2, store);

		assertTrue(store.getEtch().read(h).getStatus() >= Ref.ANNOUNCED,
				"Earned status must never be downgraded");
	}
}
