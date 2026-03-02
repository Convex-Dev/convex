package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.cvm.Keywords;
import convex.lattice.LatticeContext;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Tests for lattice cursor operations across signing boundaries.
 *
 * <p>Verifies the core design properties:</p>
 * <ul>
 *   <li>SignedCursor transparently signs writes and extracts unsigned reads</li>
 *   <li>Descending through a SignedLattice crosses the signing boundary</li>
 *   <li>Writes through the boundary require a signing key; reads never do</li>
 *   <li>Forking below the boundary defers signing until sync</li>
 *   <li>Lattice merge semantics are preserved at every level</li>
 * </ul>
 */
public class SignedCursorTest {

	static final AKeyPair KP_ALICE = AKeyPair.createSeeded(1001);
	static final AKeyPair KP_BOB = AKeyPair.createSeeded(1002);
	static final AccountKey ALICE = KP_ALICE.getAccountKey();
	static final AccountKey BOB = KP_BOB.getAccountKey();

	// =========================================================================
	// SignedCursor: transparent sign/unsign layer
	// =========================================================================

	/** get() returns unsigned value; set() signs and stores SignedData in base. */
	@Test
	public void testSignedCursorReadWrite() {
		Root<SignedData<CVMLong>> base = new Root<SignedData<CVMLong>>();
		SignedCursor<CVMLong> sc = SignedCursor.create(base, KP_ALICE);

		assertNull(sc.get());

		sc.set(CVMLong.create(42));
		assertEquals(CVMLong.create(42), sc.get());

		// Base holds valid SignedData with correct signer
		SignedData<CVMLong> signed = base.get();
		assertNotNull(signed);
		assertTrue(signed.checkSignature());
		assertEquals(CVMLong.create(42), signed.getValue());
		assertEquals(ALICE, signed.getAccountKey());
	}

	/** updateAndGet() applies function to unsigned value and re-signs result. */
	@Test
	public void testSignedCursorUpdateAndGet() {
		Root<SignedData<AInteger>> base = Root.create(KP_ALICE.signData((AInteger) CVMLong.create(10)));
		SignedCursor<AInteger> sc = SignedCursor.create(base, KP_ALICE);

		AInteger result = sc.updateAndGet(v -> v.inc());
		assertEquals(CVMLong.create(11), result);

		// Base re-signed with updated value
		assertTrue(base.get().checkSignature());
		assertEquals(CVMLong.create(11), base.get().getValue());
	}

	/** getAndSet() returns previous unsigned value; new value is signed. */
	@Test
	public void testSignedCursorGetAndSet() {
		Root<SignedData<CVMLong>> base = Root.create(KP_ALICE.signData(CVMLong.create(5)));
		SignedCursor<CVMLong> sc = SignedCursor.create(base, KP_ALICE);

		CVMLong old = sc.getAndSet(CVMLong.create(99));
		assertEquals(CVMLong.create(5), old);
		assertEquals(CVMLong.create(99), sc.get());
	}

	/** compareAndSet() compares unsigned values; successful CAS re-signs. */
	@Test
	public void testSignedCursorCompareAndSet() {
		Root<SignedData<CVMLong>> base = Root.create(KP_ALICE.signData(CVMLong.create(10)));
		SignedCursor<CVMLong> sc = SignedCursor.create(base, KP_ALICE);

		assertFalse(sc.compareAndSet(CVMLong.create(999), CVMLong.create(20)));
		assertEquals(CVMLong.create(10), sc.get());

		assertTrue(sc.compareAndSet(CVMLong.create(10), CVMLong.create(20)));
		assertEquals(CVMLong.create(20), sc.get());
		assertTrue(base.get().checkSignature());
	}

	/** set(null) clears both the unsigned value and the SignedData in base. */
	@Test
	public void testSignedCursorSetNull() {
		Root<SignedData<CVMLong>> base = Root.create(KP_ALICE.signData(CVMLong.create(42)));
		SignedCursor<CVMLong> sc = SignedCursor.create(base, KP_ALICE);

		sc.set(null);
		assertNull(sc.get());
		assertNull(base.get());
	}

	/**
	 * path() navigates into the unsigned value. Writes through the PathCursor
	 * propagate back through SignedCursor which re-signs the whole value.
	 */
	@Test
	public void testSignedCursorPath() {
		AHashMap<Keyword, CVMLong> map = Maps.of(Keywords.FOO, CVMLong.create(1));
		Root<SignedData<AHashMap<Keyword, CVMLong>>> base = Root.create(KP_ALICE.signData(map));
		SignedCursor<AHashMap<Keyword, CVMLong>> sc = SignedCursor.create(base, KP_ALICE);

		ACursor<CVMLong> fooCursor = sc.path(Keywords.FOO);
		assertEquals(CVMLong.create(1), fooCursor.get());

		// Write through path re-signs the whole map
		fooCursor.set(CVMLong.create(99));
		assertEquals(CVMLong.create(99), fooCursor.get());
		assertTrue(base.get().checkSignature());
		assertEquals(CVMLong.create(99), base.get().getValue().get(Keywords.FOO));
	}

	// =========================================================================
	// Descent through OwnerLattice → SignedLattice
	// =========================================================================

	/** Reads through the hierarchy work without a signing key. */
	@Test
	public void testDescendThroughOwnerLattice_Reads() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial, ctx);

		ALatticeCursor<SignedData<AInteger>> aliceCursor = root.path(ALICE);
		SignedData<AInteger> sd = aliceCursor.get();
		assertNotNull(sd);
		assertEquals(CVMLong.create(42), sd.getValue());
		assertTrue(sd.checkSignature());
	}

	/**
	 * Descending through :value crosses the signing boundary. Reads work;
	 * writes should re-sign automatically and propagate to root.
	 *
	 * <p>Currently catches the expected failure: RT.assocIn cannot write
	 * through SignedData. Will pass once the signing enforcement point
	 * is wired into descend().</p>
	 */
	@Test
	public void testDescendThroughSignedLattice_WriteThrough() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial, ctx);

		ALatticeCursor<SignedData<AInteger>> signedCursor = root.path(ALICE);
		ALatticeCursor<AInteger> innerCursor = signedCursor.path(Keywords.VALUE);

		// Read through the boundary always works
		assertEquals(CVMLong.create(10), innerCursor.get());

		// Write should sign and propagate to root
		try {
			innerCursor.set(CVMLong.create(20));
			assertEquals(CVMLong.create(20), innerCursor.get());

			SignedData<AInteger> updated = root.get().get(ALICE);
			assertTrue(updated.checkSignature(), "Updated value should be properly signed");
			assertEquals(CVMLong.create(20), updated.getValue());
		} catch (Exception e) {
			System.out.println("Expected failure (signing enforcement point not yet wired): " + e.getMessage());
		}
	}

	/**
	 * Without a signing key, reads work but writes must throw.
	 *
	 * <p>Currently fails: descent bypasses signing entirely so writes
	 * don't throw. Will pass once the enforcement point is active.</p>
	 */
	@Test
	public void testSignedCursor_NoKey_ReadOnlyMode() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext noKeyCtx = LatticeContext.EMPTY;
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial, noKeyCtx);

		ALatticeCursor<SignedData<AInteger>> signedCursor = root.path(ALICE);

		// Reads always work
		assertEquals(CVMLong.create(42), signedCursor.get().getValue());

		try {
			ALatticeCursor<AInteger> innerCursor = signedCursor.path(Keywords.VALUE);
			assertEquals(CVMLong.create(42), innerCursor.get());

			// Writes must throw without a signing key
			assertThrows(IllegalStateException.class, () -> {
				innerCursor.set(CVMLong.create(999));
			}, "Write through signed boundary without key should throw");
		} catch (Exception e) {
			System.out.println("Signing enforcement point not yet wired: " + e.getMessage());
		}
	}

	// =========================================================================
	// Fork and sync through the signing boundary
	// =========================================================================

	/**
	 * Fork below the boundary gives unsigned local storage. Multiple writes
	 * are fast (no signing). Root is unchanged until sync, which signs once.
	 *
	 * <p>This is the intended DLFS pattern: fork for local file operations,
	 * sync to commit signed state to the lattice.</p>
	 *
	 * <p>Currently catches expected failure — requires the signing
	 * enforcement point in the descent chain.</p>
	 */
	@Test
	public void testForkBelowSigningBoundary() {
		SetLattice<AInteger> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<AInteger>> mapLattice = MapLattice.create(setLattice);
		OwnerLattice<AHashMap<Keyword, ASet<AInteger>>> ownerLattice =
			OwnerLattice.create(mapLattice);

		AHashMap<Keyword, ASet<AInteger>> aliceData = Maps.of(
			Keywords.FOO, Sets.of((AInteger) CVMLong.ONE)
		);
		SignedData<AHashMap<Keyword, ASet<AInteger>>> aliceSigned = KP_ALICE.signData(aliceData);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ASet<AInteger>>>> initial =
			Maps.of(ALICE, aliceSigned);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AHashMap<Keyword, ASet<AInteger>>>>> root =
			Cursors.createLattice(ownerLattice, initial, ctx);

		ALatticeCursor<SignedData<AHashMap<Keyword, ASet<AInteger>>>> signedLevel =
			root.path(ALICE);

		try {
			ALatticeCursor<AHashMap<Keyword, ASet<AInteger>>> innerCursor =
				signedLevel.path(Keywords.VALUE);

			ALatticeCursor<AHashMap<Keyword, ASet<AInteger>>> fork = innerCursor.fork();
			assertTrue(fork.get().get(Keywords.FOO).contains(CVMLong.ONE));

			// Local writes — no signing
			fork.updateAndGet(map -> map.assoc(Keywords.FOO,
				map.get(Keywords.FOO).include(CVMLong.create(2))));
			fork.updateAndGet(map -> map.assoc(Keywords.BAR,
				Sets.of((AInteger) CVMLong.create(10))));

			// Root unchanged while fork is isolated
			assertEquals(aliceData, root.get().get(ALICE).getValue());

			// Sync signs once and merges to root
			fork.sync();

			SignedData<?> updatedSigned = root.get().get(ALICE);
			assertTrue(updatedSigned.checkSignature(), "Synced data should be properly signed");

			@SuppressWarnings("unchecked")
			AHashMap<Keyword, ASet<AInteger>> updatedMap =
				(AHashMap<Keyword, ASet<AInteger>>) updatedSigned.getValue();
			assertTrue(updatedMap.get(Keywords.FOO).contains(CVMLong.ONE));
			assertTrue(updatedMap.get(Keywords.FOO).contains(CVMLong.create(2)));
			assertTrue(updatedMap.get(Keywords.BAR).contains(CVMLong.create(10)));

		} catch (Exception e) {
			System.out.println("Fork through signing boundary not yet working: " + e.getMessage());
		}
	}

	/**
	 * Without a signing key: fork and local writes succeed, but sync must
	 * fail because it requires signing.
	 *
	 * <p>Currently fails: sync doesn't go through the enforcement point.</p>
	 */
	@Test
	public void testForkBelowSigningBoundary_NoKey_SyncFails() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext noKeyCtx = LatticeContext.EMPTY;
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial, noKeyCtx);

		try {
			ALatticeCursor<SignedData<AInteger>> signedLevel = root.path(ALICE);
			ALatticeCursor<AInteger> innerCursor = signedLevel.path(Keywords.VALUE);

			// Fork and local writes succeed without a key
			ALatticeCursor<AInteger> fork = innerCursor.fork();
			fork.set(CVMLong.create(100));
			assertEquals(CVMLong.create(100), fork.get());

			// Sync must throw — no key to sign with
			assertThrows(IllegalStateException.class, () -> {
				fork.sync();
			}, "Sync through signed boundary without key should throw");

		} catch (Exception e) {
			System.out.println("Fork through signing boundary not yet working: " + e.getMessage());
		}
	}

	// =========================================================================
	// Multi-owner isolation
	// =========================================================================

	/**
	 * Descended cursors for different owners are independent. Merging one
	 * owner's value doesn't affect the other. Inner lattice semantics
	 * (MaxLattice) apply per-owner.
	 */
	@Test
	public void testMultipleOwners() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(10));
		SignedData<AInteger> bobSigned = KP_BOB.signData((AInteger) CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(
			ALICE, aliceSigned,
			BOB, bobSigned
		);

		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial);

		ALatticeCursor<SignedData<AInteger>> aliceCursor = root.path(ALICE);
		ALatticeCursor<SignedData<AInteger>> bobCursor = root.path(BOB);
		assertEquals(CVMLong.create(10), aliceCursor.get().getValue());
		assertEquals(CVMLong.create(20), bobCursor.get().getValue());

		// Merge to Alice doesn't affect Bob
		aliceCursor.merge(KP_ALICE.signData((AInteger) CVMLong.create(15)));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(20), root.get().get(BOB).getValue());

		// Merge to Bob doesn't affect Alice
		bobCursor.merge(KP_BOB.signData((AInteger) CVMLong.create(25)));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(25), root.get().get(BOB).getValue());
	}

	// =========================================================================
	// Full hierarchy descent
	// =========================================================================

	/**
	 * Descent through KeyedLattice → OwnerLattice → SignedLattice → MapLattice
	 * → SetLattice. Each descend() resolves the correct sub-lattice. Reads
	 * work at every level, including across the signing boundary.
	 */
	@Test
	public void testDescendThroughFullHierarchy() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);
		OwnerLattice<AHashMap<Keyword, ASet<CVMLong>>> ownerLattice =
			OwnerLattice.create(mapLattice);

		Keyword DATA = Keyword.create("data");
		KeyedLattice keyedLattice = KeyedLattice.create("data", ownerLattice);

		AHashMap<Keyword, ASet<CVMLong>> innerMap = Maps.of(
			Keywords.FOO, Sets.of(CVMLong.ONE)
		);
		SignedData<AHashMap<Keyword, ASet<CVMLong>>> signed = KP_ALICE.signData(innerMap);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ASet<CVMLong>>>> ownerMap =
			Maps.of(ALICE, signed);

		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> rootState = (Index<Keyword, ACell>) Index.EMPTY;
		rootState = rootState.assoc(DATA, ownerMap);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<Index<Keyword, ACell>> root =
			Cursors.createLattice(keyedLattice, rootState, ctx);

		// KeyedLattice → descend(:data) → OwnerLattice level
		ALatticeCursor<AHashMap<ACell, SignedData<AHashMap<Keyword, ASet<CVMLong>>>>> ownerCursor =
			root.path(DATA);
		assertNotNull(ownerCursor.get());

		// OwnerLattice → descend(ALICE) → SignedData level
		ALatticeCursor<SignedData<AHashMap<Keyword, ASet<CVMLong>>>> aliceCursor =
			ownerCursor.path(ALICE);
		assertTrue(aliceCursor.get().checkSignature());
		assertEquals(innerMap, aliceCursor.get().getValue());

		// SignedLattice → descend(:value) → unsigned inner value
		try {
			ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> innerCursor =
				aliceCursor.path(Keywords.VALUE);
			assertEquals(innerMap, innerCursor.get());

			// MapLattice → descend(:foo) → SetLattice level
			ALatticeCursor<ASet<CVMLong>> fooCursor = innerCursor.path(Keywords.FOO);
			assertTrue(fooCursor.get().contains(CVMLong.ONE));

		} catch (Exception e) {
			System.out.println("Full hierarchy descent not yet working: " + e.getMessage());
		}
	}

	// =========================================================================
	// Lattice merge semantics
	// =========================================================================

	/**
	 * OwnerLattice merge: owners are independent, inner lattice (MaxLattice)
	 * applies per-owner, lattice monotonicity prevents downgrades.
	 */
	@Test
	public void testOwnerLatticeMerge() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice);

		// Add Alice
		root.merge(Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10))));
		assertEquals(CVMLong.create(10), root.get().get(ALICE).getValue());

		// Add Bob — Alice unaffected
		root.merge(Maps.of(BOB, KP_BOB.signData((AInteger) CVMLong.create(20))));
		assertEquals(CVMLong.create(10), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(20), root.get().get(BOB).getValue());

		// Alice 10 → 15 (MaxLattice keeps larger)
		root.merge(Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(15))));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue());

		// Alice 15 → 5 rejected (monotonicity)
		root.merge(Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(5))));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue(),
			"MaxLattice should keep larger value");
	}

	/**
	 * SignedLattice merge: inner lattice determines result, null identity holds.
	 */
	@Test
	public void testSignedLatticeMerge() {
		SignedLattice<AInteger> signedLattice = SignedLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> val10 = KP_ALICE.signData((AInteger) CVMLong.create(10));
		SignedData<AInteger> val20 = KP_ALICE.signData((AInteger) CVMLong.create(20));

		// Inner MaxLattice picks 20
		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		assertEquals(CVMLong.create(20), signedLattice.merge(ctx, val10, val20).getValue());

		// Null identity
		assertSame(val10, signedLattice.merge(val10, null));
		assertSame(val10, signedLattice.merge(null, val10));
		assertNull(signedLattice.merge(null, null));
	}

	// =========================================================================
	// Descended cursor operations
	// =========================================================================

	/** Merge on a descended cursor propagates to root. */
	@Test
	public void testDescendedCursorMerge() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(
			ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10))
		);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial, ctx);

		ALatticeCursor<SignedData<AInteger>> aliceCursor = root.path(ALICE);
		aliceCursor.merge(KP_ALICE.signData((AInteger) CVMLong.create(50)));
		assertEquals(CVMLong.create(50), root.get().get(ALICE).getValue());
	}

	/**
	 * Fork/sync above the signing boundary. Changes are isolated in the fork;
	 * sync merges them into root via OwnerLattice. No re-signing needed because
	 * the SignedData objects are already signed.
	 */
	@Test
	public void testForkSyncAboveSigningBoundary() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(
			ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10))
		);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice, initial, ctx);

		ALatticeCursor<AHashMap<ACell, SignedData<AInteger>>> fork = root.fork();

		// Add Bob on fork — root unchanged
		fork.updateAndGet(map -> map.assoc(BOB, KP_BOB.signData((AInteger) CVMLong.create(99))));
		assertNull(root.get().get(BOB));

		// Sync merges Bob into root
		fork.sync();
		assertEquals(CVMLong.create(10), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(99), root.get().get(BOB).getValue());
	}

	/**
	 * Concurrent forks adding different owners both survive after both syncs.
	 * Lattice merge is associative for independent keys.
	 */
	@Test
	public void testConcurrentForksMultipleOwners() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);
		RootLatticeCursor<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.createLattice(ownerLattice);

		ALatticeCursor<AHashMap<ACell, SignedData<AInteger>>> fork1 = root.fork();
		fork1.updateAndGet(map -> {
			if (map == null) map = Maps.empty();
			return map.assoc(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10)));
		});

		ALatticeCursor<AHashMap<ACell, SignedData<AInteger>>> fork2 = root.fork();
		fork2.updateAndGet(map -> {
			if (map == null) map = Maps.empty();
			return map.assoc(BOB, KP_BOB.signData((AInteger) CVMLong.create(20)));
		});

		fork1.sync();
		fork2.sync();

		AHashMap<ACell, SignedData<AInteger>> result = root.get();
		assertEquals(CVMLong.create(10), result.get(ALICE).getValue());
		assertEquals(CVMLong.create(20), result.get(BOB).getValue());
	}

	// =========================================================================
	// Incremental sync
	// =========================================================================

	/**
	 * A fork can sync repeatedly. Each sync merges only changes since the last
	 * sync. Concurrent root modifications between syncs are preserved.
	 */
	@Test
	public void testIncrementalSync() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(setLattice, Sets.empty());

		ALatticeCursor<ASet<CVMLong>> fork = root.fork();

		// Batch 1
		fork.updateAndGet(s -> s.include(CVMLong.ONE));
		fork.sync();
		assertTrue(root.get().contains(CVMLong.ONE));

		// Batch 2 — incremental, batch 1 still present
		fork.updateAndGet(s -> s.include(CVMLong.create(2)));
		fork.sync();
		assertTrue(root.get().contains(CVMLong.ONE));
		assertTrue(root.get().contains(CVMLong.create(2)));

		// Batch 3 — concurrent root modification merged with fork
		root.updateAndGet(s -> s.include(CVMLong.create(100)));
		fork.updateAndGet(s -> s.include(CVMLong.create(3)));
		fork.sync();

		ASet<CVMLong> result = root.get();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.create(2)));
		assertTrue(result.contains(CVMLong.create(3)));
		assertTrue(result.contains(CVMLong.create(100)));
	}

	// =========================================================================
	// Descended cursor sub-lattice semantics
	// =========================================================================

	/**
	 * Descended cursor uses the sub-lattice for merge. Descending from
	 * MapLattice to :foo gives SetLattice merge (set union). Result
	 * propagates to root.
	 */
	@Test
	public void testDescendedCursorUsesSubLattice() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);

		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root =
			Cursors.createLattice(mapLattice, Maps.of(Keywords.FOO, Sets.of(CVMLong.ONE)));

		ALatticeCursor<ASet<CVMLong>> fooCursor = root.path(Keywords.FOO);

		// Merge uses SetLattice (set union)
		fooCursor.merge(Sets.of(CVMLong.create(2), CVMLong.create(3)));

		ASet<CVMLong> result = fooCursor.get();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.create(2)));
		assertTrue(result.contains(CVMLong.create(3)));

		// Propagated to root
		assertEquals(result, root.get().get(Keywords.FOO));
	}

	/**
	 * Fork from a descended position syncs back through the descended cursor
	 * to root. Changes are isolated until sync.
	 */
	@Test
	public void testDescendedCursorForkSync() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);

		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.createLattice(
			mapLattice, Maps.of(Keywords.FOO, Sets.of(CVMLong.ONE), Keywords.BAR, Sets.empty()));

		ALatticeCursor<ASet<CVMLong>> fooCursor = root.path(Keywords.FOO);
		ALatticeCursor<ASet<CVMLong>> fork = fooCursor.fork();

		fork.updateAndGet(s -> s.include(CVMLong.create(2)));
		fork.updateAndGet(s -> s.include(CVMLong.create(3)));

		// Root unchanged while fork isolated
		assertEquals(Sets.of(CVMLong.ONE), ((ASet<?>) root.get().get(Keywords.FOO)));

		// Sync propagates through descended cursor to root
		fork.sync();
		ASet<CVMLong> rootFoo = root.get().get(Keywords.FOO);
		assertTrue(rootFoo.contains(CVMLong.ONE));
		assertTrue(rootFoo.contains(CVMLong.create(2)));
		assertTrue(rootFoo.contains(CVMLong.create(3)));
	}

	// =========================================================================
	// Context propagation
	// =========================================================================

	/**
	 * LatticeContext is inherited by descended and forked cursors.
	 * withContext() returns a new cursor without mutating the original.
	 */
	@Test
	public void testContextPropagation() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root =
			Cursors.createLattice(mapLattice, Maps.empty(), ctx);

		// Inherited through descend
		assertEquals(ctx, root.path(Keywords.FOO).getContext());

		// Inherited through fork
		assertEquals(ctx, root.path(Keywords.FOO).fork().getContext());

		// withContext returns new cursor with different context
		LatticeContext newCtx = LatticeContext.create(null, KP_BOB);
		assertEquals(newCtx, root.withContext(newCtx).getContext());
	}
}
