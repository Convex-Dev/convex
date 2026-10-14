package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

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
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Tests for lattice cursor operations across signing boundaries.
 *
 * <p>Verifies the core design properties:</p>
 * <ul>
 *   <li>SignedCursor transparently signs writes and extracts unsigned reads</li>
 *   <li>The component owning an owner level wraps each slot with {@code SignedCursor.forOwner};
 *       plain navigation never crosses the boundary</li>
 *   <li>Writes through the boundary require a signing key; reads never do</li>
 *   <li>Forking below the boundary defers signing until sync</li>
 *   <li>Lattice merge semantics are preserved at every level</li>
 * </ul>
 */
public class SignedCursorTest {

	/**
	 * A contended write must sign once, not once per compare-and-set attempt: the
	 * signing policy may reach a wallet, key store or remote signer.
	 */
	@Test
	public void testContendedWriteSignsOnce() {
		AtomicInteger signs=new AtomicInteger();
		LatticeContext counting=new LatticeContext() {
			@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey,T value) {
				signs.incrementAndGet();
				return KP_ALICE.signData(value);
			}
		};
		SignedLattice<AInteger> signedLattice=SignedLattice.create(MaxLattice.INSTANCE);
		Root<SignedData<AInteger>> base=Cursors.root(
			KP_ALICE.signData((AInteger)CVMLong.ONE),signedLattice,counting);
		SignedCursor<AInteger> cursor=SignedCursor.create(base,signedLattice,null);

		// Displace the base value on the first attempt only, forcing exactly one CAS retry
		AtomicInteger attempts=new AtomicInteger();
		AInteger result=cursor.updateAndGet(current -> {
			if (attempts.getAndIncrement()==0) base.set(KP_ALICE.signData((AInteger)CVMLong.TWO));
			return CVMLong.create(99);
		});

		assertEquals(2,attempts.get(),"the update function was retried");
		assertEquals(CVMLong.create(99),result);
		assertEquals(1,signs.get(),"one signature for one logical write");
	}

	/** An unchanged write keeps the existing signature and never asks for a new one. */
	@Test
	public void testUnchangedWriteDoesNotSign() {
		AtomicInteger signs=new AtomicInteger();
		LatticeContext counting=new LatticeContext() {
			@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey,T value) {
				signs.incrementAndGet();
				return KP_ALICE.signData(value);
			}
		};
		SignedData<AInteger> initial=KP_ALICE.signData((AInteger)CVMLong.ONE);
		SignedLattice<AInteger> signedLattice=SignedLattice.create(MaxLattice.INSTANCE);
		Root<SignedData<AInteger>> base=Cursors.root(initial,signedLattice,counting);
		SignedCursor<AInteger> cursor=SignedCursor.create(base,signedLattice,null);

		cursor.set(CVMLong.create(1));

		assertEquals(0,signs.get(),"no re-signing for an unchanged value");
		assertSame(initial,base.get(),"the stored cell is untouched");
	}

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

	/** Accumulator arg order must match the ACursor contract: apply(current, x). */
	@Test
	public void testSignedCursorAccumulateArgOrder() {
		Root<SignedData<CVMLong>> base = Root.create(KP_ALICE.signData(CVMLong.create(10)));
		SignedCursor<CVMLong> sc = SignedCursor.create(base, KP_ALICE);
		java.util.function.BinaryOperator<CVMLong> minus =
			(cur, x) -> CVMLong.create(cur.longValue() - x.longValue());

		assertEquals(CVMLong.create(7), sc.accumulateAndGet(CVMLong.create(3), minus)); // 10 - 3
		assertTrue(base.get().checkSignature());
		assertEquals(CVMLong.create(7), sc.getAndAccumulate(CVMLong.create(2), minus)); // returns old 7; sets 5
		assertEquals(CVMLong.create(5), sc.get());
	}

	/** A write of an equal value (different object) must not re-sign — the existing
	 *  SignedData is kept, avoiding the expensive crypto op. Only a real change re-signs. */
	@Test
	public void testUnchangedWriteSkipsResign() {
		Root<SignedData<CVMLong>> base = Root.create(KP_ALICE.signData(CVMLong.create(5)));
		SignedData<CVMLong> before = base.get();
		SignedCursor<CVMLong> sc = SignedCursor.create(base, KP_ALICE);

		sc.updateAndGet(v -> CVMLong.create(5));   // equal value, new object
		assertSame(before, base.get(), "no-op update must not re-sign");

		sc.set(CVMLong.create(5));                 // set to equal value
		assertSame(before, base.get(), "no-op set must not re-sign");

		sc.updateAndGet(v -> CVMLong.create(6));   // real change
		assertNotSame(before, base.get());
		assertEquals(CVMLong.create(6), base.get().getValue());
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
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, ctx);

		AForkableCursor<SignedData<AInteger>> aliceCursor = root.path(ALICE);
		SignedData<AInteger> sd = aliceCursor.get();
		assertNotNull(sd);
		assertEquals(CVMLong.create(42), sd.getValue());
		assertTrue(sd.checkSignature());
	}

	/**
	 * Descending through :value crosses the signing boundary. Reads work;
	 * writes re-sign automatically and propagate to root.
	 */
	@Test
	public void testDescendThroughSignedLattice_WriteThrough() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, ctx);

		AForkableCursor<SignedData<AInteger>> signedCursor = root.path(ALICE);
		AForkableCursor<AInteger> innerCursor = SignedCursor.forOwner(signedCursor, ownerLattice, ALICE);

		// Read through the boundary always works
		assertEquals(CVMLong.create(10), innerCursor.get());

		// Write signs at the boundary and propagates to root
		innerCursor.set(CVMLong.create(20));
		assertEquals(CVMLong.create(20), innerCursor.get());

		SignedData<AInteger> updated = root.get().get(ALICE);
		assertTrue(updated.checkSignature(), "Updated value should be properly signed");
		assertEquals(CVMLong.create(20), updated.getValue());
	}

	@Test
	public void testOwnerPathRequestsNonPrimarySigner() {
		OwnerLattice<AInteger> ownerLattice=OwnerLattice.create(MaxLattice.INSTANCE);
		AHashMap<ACell,SignedData<AInteger>> initial=Maps.of(
			ALICE,KP_ALICE.signData((AInteger)CVMLong.ONE),
			BOB,KP_BOB.signData((AInteger)CVMLong.TWO));
		LatticeContext wallet=new LatticeContext() {
			@Override public AKeyPair getSigningKey() {
				return KP_ALICE;
			}

			@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey,T value) {
				if (accountKey!=null && accountKey.equals(BOB)) return KP_BOB.signData(value);
				return super.sign(accountKey,value);
			}
		};
		Root<AHashMap<ACell,SignedData<AInteger>>> root=
			Cursors.root(initial, ownerLattice, wallet);

		SignedCursor.forOwner(root.<SignedData<AInteger>>path(BOB), ownerLattice, BOB)
			.set(CVMLong.create(99));

		SignedData<AInteger> updated=root.get().get(BOB);
		assertEquals(BOB,updated.getAccountKey());
		assertTrue(updated.checkSignature());
		assertEquals(CVMLong.create(99),updated.getValue());
	}

	/**
	 * Without a signing key, reads work but writes must throw.
	 */
	@Test
	public void testSignedCursor_NoKey_ReadOnlyMode() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext noKeyCtx = LatticeContext.EMPTY;
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, noKeyCtx);

		AForkableCursor<SignedData<AInteger>> signedCursor = root.path(ALICE);

		// Reads always work
		assertEquals(CVMLong.create(42), signedCursor.get().getValue());

		AForkableCursor<AInteger> innerCursor = SignedCursor.forOwner(signedCursor, ownerLattice, ALICE);
		assertEquals(CVMLong.create(42), innerCursor.get());

		// Writes must throw without a signing key
		assertThrows(IllegalStateException.class,
			() -> innerCursor.set(CVMLong.create(999)),
			"Write through signed boundary without key should throw");
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
		Root<AHashMap<ACell, SignedData<AHashMap<Keyword, ASet<AInteger>>>>> root =
			Cursors.root(initial, ownerLattice, ctx);

		AForkableCursor<SignedData<AHashMap<Keyword, ASet<AInteger>>>> signedLevel =
			root.path(ALICE);

		AForkableCursor<AHashMap<Keyword, ASet<AInteger>>> innerCursor =
			SignedCursor.forOwner(signedLevel, ownerLattice, ALICE);

		AForkableCursor<AHashMap<Keyword, ASet<AInteger>>> fork = innerCursor.fork(mapLattice);
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
	}

	/**
	 * Without a signing key: fork and local writes succeed, but sync must
	 * fail because it requires signing.
	 */
	@Test
	public void testForkBelowSigningBoundary_NoKey_SyncFails() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		LatticeContext noKeyCtx = LatticeContext.EMPTY;
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, noKeyCtx);

		AForkableCursor<SignedData<AInteger>> signedLevel = root.path(ALICE);
		AForkableCursor<AInteger> innerCursor = SignedCursor.forOwner(signedLevel, ownerLattice, ALICE);

		// Fork and local writes succeed without a key
		AForkableCursor<AInteger> fork = innerCursor.fork(MaxLattice.INSTANCE);
		fork.set(CVMLong.create(100));
		assertEquals(CVMLong.create(100), fork.get());

		// Sync must throw — no key to sign with
		assertThrows(IllegalStateException.class, () -> fork.sync(),
			"Sync through signed boundary without key should throw");
	}

	// =========================================================================
	// Multi-owner isolation
	// =========================================================================

	/**
	 * Path cursors for different owners are independent. Merging one
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

		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, null);

		AForkableCursor<SignedData<AInteger>> aliceCursor = root.path(ALICE);
		AForkableCursor<SignedData<AInteger>> bobCursor = root.path(BOB);
		assertEquals(CVMLong.create(10), aliceCursor.get().getValue());
		assertEquals(CVMLong.create(20), bobCursor.get().getValue());

		// Merge to Alice doesn't affect Bob
		aliceCursor.merge(ownerLattice.signingFor(ALICE), KP_ALICE.signData((AInteger) CVMLong.create(15)));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(20), root.get().get(BOB).getValue());

		// Merge to Bob doesn't affect Alice
		bobCursor.merge(ownerLattice.signingFor(BOB), KP_BOB.signData((AInteger) CVMLong.create(25)));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(25), root.get().get(BOB).getValue());
	}

	// =========================================================================
	// Full hierarchy descent
	// =========================================================================

	/**
	 * Descent through KeyedLattice → OwnerLattice → SignedLattice → MapLattice
	 * → SetLattice. Plain paths read at every level; the component owning the owner
	 * level wraps the slot to cross the signing boundary.
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
		Root<Index<Keyword, ACell>> root =
			Cursors.root(rootState, keyedLattice, ctx);

		// KeyedLattice → path(:data) → OwnerLattice level
		AForkableCursor<AHashMap<ACell, SignedData<AHashMap<Keyword, ASet<CVMLong>>>>> ownerCursor =
			root.path(DATA);
		assertNotNull(ownerCursor.get());

		// OwnerLattice → path(ALICE) → SignedData level
		AForkableCursor<SignedData<AHashMap<Keyword, ASet<CVMLong>>>> aliceCursor =
			ownerCursor.path(ALICE);
		assertTrue(aliceCursor.get().checkSignature());
		assertEquals(innerMap, aliceCursor.get().getValue());

		// SignedLattice → the component owning the owner level wraps the slot
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> innerCursor =
			SignedCursor.forOwner(aliceCursor, ownerLattice, ALICE);
		assertEquals(innerMap, innerCursor.get());

		// MapLattice → path(:foo) → SetLattice level
		AForkableCursor<ASet<CVMLong>> fooCursor = innerCursor.path(Keywords.FOO);
		assertTrue(fooCursor.get().contains(CVMLong.ONE));
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
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(ownerLattice);

		// Add Alice
		root.merge(ownerLattice, Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10))));
		assertEquals(CVMLong.create(10), root.get().get(ALICE).getValue());

		// Add Bob — Alice unaffected
		root.merge(ownerLattice, Maps.of(BOB, KP_BOB.signData((AInteger) CVMLong.create(20))));
		assertEquals(CVMLong.create(10), root.get().get(ALICE).getValue());
		assertEquals(CVMLong.create(20), root.get().get(BOB).getValue());

		// Alice 10 → 15 (MaxLattice keeps larger)
		root.merge(ownerLattice, Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(15))));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue());

		// Alice 15 → 5 rejected (monotonicity)
		root.merge(ownerLattice, Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(5))));
		assertEquals(CVMLong.create(15), root.get().get(ALICE).getValue(),
			"MaxLattice should keep larger value");
	}

	@Test
	public void testOwnerMergeSynthesisRequestsNonPrimarySigner() {
		OwnerLattice<ASet<CVMLong>> ownerLattice=OwnerLattice.create(SetLattice.create());
		LatticeContext wallet=new LatticeContext() {
			@Override public AKeyPair getSigningKey() {
				return KP_ALICE;
			}

			@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey,T value) {
				if (accountKey!=null && accountKey.equals(BOB)) return KP_BOB.signData(value);
				return super.sign(accountKey,value);
			}
		};
		AHashMap<ACell,SignedData<ASet<CVMLong>>> own=Maps.of(
			BOB,KP_BOB.signData(Sets.of(CVMLong.ONE)));
		AHashMap<ACell,SignedData<ASet<CVMLong>>> other=Maps.of(
			BOB,KP_BOB.signData(Sets.of(CVMLong.TWO)));

		SignedData<ASet<CVMLong>> merged=ownerLattice.merge(wallet,own,other).get(BOB);

		assertEquals(BOB,merged.getAccountKey());
		assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),merged.getValue());
		assertTrue(merged.checkSignature());
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
	// Path cursor operations
	// =========================================================================

	/** Merge on a path cursor, with the slot's signed lattice, propagates to root. */
	@Test
	public void testPathCursorMergePropagatesToRoot() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(
			ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10))
		);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, ctx);

		AForkableCursor<SignedData<AInteger>> aliceCursor = root.path(ALICE);
		aliceCursor.merge(ownerLattice.signingFor(ALICE), KP_ALICE.signData((AInteger) CVMLong.create(50)));
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
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, ctx);

		AForkableCursor<AHashMap<ACell, SignedData<AInteger>>> fork = root.fork(ownerLattice);

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
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(ownerLattice);

		AForkableCursor<AHashMap<ACell, SignedData<AInteger>>> fork1 = root.fork(ownerLattice);
		fork1.updateAndGet(map -> {
			if (map == null) map = Maps.empty();
			return map.assoc(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(10)));
		});

		AForkableCursor<AHashMap<ACell, SignedData<AInteger>>> fork2 = root.fork(ownerLattice);
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
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty(), setLattice, null);

		AForkableCursor<ASet<CVMLong>> fork = root.fork(setLattice);

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
	// Path cursor merges with the sub-lattice the component supplies
	// =========================================================================

	/**
	 * A path cursor merges with the sub-lattice its component supplies: at :foo
	 * below a MapLattice that is SetLattice merge (set union). The result
	 * propagates to root.
	 */
	@Test
	public void testPathCursorMergeUsesSubLattice() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);

		Root<AHashMap<Keyword, ASet<CVMLong>>> root =
			Cursors.root(Maps.of(Keywords.FOO, Sets.of(CVMLong.ONE)), mapLattice, null);

		AForkableCursor<ASet<CVMLong>> fooCursor = root.path(Keywords.FOO);

		// Merge uses SetLattice (set union)
		fooCursor.merge(setLattice, Sets.of(CVMLong.create(2), CVMLong.create(3)));

		ASet<CVMLong> result = fooCursor.get();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.create(2)));
		assertTrue(result.contains(CVMLong.create(3)));

		// Propagated to root
		assertEquals(result, root.get().get(Keywords.FOO));
	}

	/**
	 * Fork from a path position syncs back through the path cursor to root,
	 * merging with the sub-lattice given at fork time. Changes are isolated until sync.
	 */
	@Test
	public void testPathCursorForkSync() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);

		Root<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.root(
			Maps.of(Keywords.FOO, Sets.of(CVMLong.ONE), Keywords.BAR, Sets.empty()), mapLattice, null);

		AForkableCursor<ASet<CVMLong>> fooCursor = root.path(Keywords.FOO);
		AForkableCursor<ASet<CVMLong>> fork = fooCursor.fork(setLattice);

		fork.updateAndGet(s -> s.include(CVMLong.create(2)));
		fork.updateAndGet(s -> s.include(CVMLong.create(3)));

		// Root unchanged while fork isolated
		assertEquals(Sets.of(CVMLong.ONE), ((ASet<?>) root.get().get(Keywords.FOO)));

		// Sync propagates through the path cursor to root
		fork.sync();
		ASet<CVMLong> rootFoo = root.get().get(Keywords.FOO);
		assertTrue(rootFoo.contains(CVMLong.ONE));
		assertTrue(rootFoo.contains(CVMLong.create(2)));
		assertTrue(rootFoo.contains(CVMLong.create(3)));
	}

	// =========================================================================
	// Sync propagation through signing boundary
	// =========================================================================

	/**
	 * sync() on a SignedCursor must propagate to the base cursor.
	 * Verifies the onSync callback fires on the root.
	 */
	@Test
	public void testSyncPropagatesThroughSignedCursor() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);

		SignedData<AInteger> aliceSigned = KP_ALICE.signData((AInteger) CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> initial = Maps.of(ALICE, aliceSigned);

		java.util.concurrent.atomic.AtomicInteger syncCount = new java.util.concurrent.atomic.AtomicInteger();
		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, ctx);
		root.onSync(value -> { syncCount.incrementAndGet(); return value; }, ownerLattice);

		// Navigate to the owner's slot, then wrap it in the signing boundary
		AForkableCursor<AInteger> innerCursor = SignedCursor.forOwner(root.path(ALICE), ownerLattice, ALICE);

		// Write and sync from inside the signing boundary
		innerCursor.set(CVMLong.create(99));
		innerCursor.sync();

		assertTrue(syncCount.get() > 0,
			"sync() from inside signing boundary must reach root onSync callback");
	}

	/**
	 * Navigation never crosses a signing boundary on its own. A plain path through
	 * the envelope can still read the value, but it has no sub-lattice and every
	 * write through it fails, so an unsigned write cannot reach the lattice by
	 * mistake. The component that owns the owner level wraps the slot instead.
	 */
	@Test
	public void testPlainPathThroughSignedSlotReadsButCannotWrite() {
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.INSTANCE);
		AHashMap<ACell, SignedData<AInteger>> initial =
			Maps.of(ALICE, KP_ALICE.signData((AInteger) CVMLong.create(42)));
		Root<AHashMap<ACell, SignedData<AInteger>>> root =
			Cursors.root(initial, ownerLattice, LatticeContext.create(null, KP_ALICE));

		AForkableCursor<AInteger> unwrapped = root.path(ALICE, Keywords.VALUE);
		assertEquals(CVMLong.create(42), unwrapped.get());

		AHashMap<ACell, SignedData<AInteger>> before = root.get();
		assertThrows(IllegalArgumentException.class, () -> unwrapped.set(CVMLong.create(43)));
		assertSame(before, root.get(), "a failed unsigned write must leave the root untouched");
		assertThrows(IllegalArgumentException.class,
			() -> SignedCursor.forOwner(root.path(ALICE), OwnerKeyLattice.create(MaxLattice.INSTANCE), Keywords.VALUE),
			"an invalid owner key for the lattice is rejected when wrapping");
	}

	/**
	 * sync() throws IllegalStateException if the base cursor is not forkable, so it
	 * has no parent to propagate to.
	 */
	@Test
	public void testSyncThrowsOnNonForkableBase() {
		Root<SignedData<CVMLong>> root = Root.create(KP_ALICE.signData(CVMLong.create(1)));
		ACursor<SignedData<CVMLong>> view = new TimeCache<>(root, 1000);
		SignedCursor<CVMLong> sc = SignedCursor.create(view, KP_ALICE);

		assertEquals(CVMLong.create(1), sc.get());
		assertThrows(IllegalStateException.class, () -> sc.sync(),
			"sync() on a SignedCursor over a read-only view has nowhere to propagate");
	}

	// =========================================================================
	// Context propagation
	// =========================================================================

	/**
	 * Path cursors inherit their parent's effective context and forks capture it.
	 * setContext() mutates the cursor's local context override.
	 */
	@Test
	public void testContextPropagation() {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(setLattice);

		LatticeContext ctx = LatticeContext.create(null, KP_ALICE);
		Root<AHashMap<Keyword, ASet<CVMLong>>> root =
			Cursors.root(Maps.empty(), mapLattice, ctx);

		// Inherited through descend
		assertEquals(ctx, root.path(Keywords.FOO).getContext());

		// Inherited through fork
		assertEquals(ctx, root.<ASet<CVMLong>>path(Keywords.FOO).fork(setLattice).getContext());

		// setContext mutates the root's local context override
		LatticeContext newCtx = LatticeContext.create(null, KP_BOB);
		assertEquals(newCtx, root.setContext(newCtx).getContext());
	}
}
