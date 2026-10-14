package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BiPredicate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.cvm.CVMEncoder;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.SignedCursor;

/** Ownership rules survive container choice, navigation, contexts and fork/sync. */
public class OwnershipHierarchyTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(4101);
	private static final AKeyPair BOB=AKeyPair.createSeeded(4102);
	private static final AString DID=Strings.create("did:convex:1337");
	private static final BiPredicate<AString,AccountKey> ALICE_RULE=
		(owner,key) -> DID.equals(owner) && ALICE.getAccountKey().equals(key);

	@Test
	public void testMapAndIndexVerification() {
		checkVerification(OwnerMapLattice.create(MaxLattice.INSTANCE,ALICE_RULE));
		checkVerification(OwnerIndexLattice.create(MaxLattice.INSTANCE,ALICE_RULE));
	}

	private <M extends AMap<AString,SignedData<AInteger>>> void checkVerification(
			AOwnerLattice<AString,AInteger,M> lattice) {
		SignedData<AInteger> good=ALICE.signData(CVMLong.ONE);
		SignedData<AInteger> bad=BOB.signData(CVMLong.TWO);
		M incoming=entry(lattice.zero(),DID,good);
		M accepted=lattice.merge(null,incoming);
		assertSame(good,accepted.get(DID));
		assertEquals(lattice.zero().getClass(),accepted.getClass());
		assertSame(accepted,lattice.merge(accepted,incoming));
		assertSame(accepted,lattice.merge(accepted,entry(lattice.zero(),DID,bad)));
		assertEquals(lattice.zero(),lattice.merge(null,entry(lattice.zero(),DID,bad)));

		// An unrelated context cannot grant or revoke this region's owner authority.
		LatticeContext allowAll=LatticeContext.create(null,BOB,(o,k) -> true);
		LatticeContext denyAll=LatticeContext.create(null,ALICE,(o,k) -> false);
		assertEquals(lattice.zero(),lattice.merge(allowAll,null,entry(lattice.zero(),DID,bad)));
		assertEquals(accepted,lattice.merge(denyAll,null,incoming));
		assertEquals(lattice.zero(),lattice.merge(null,
			entry(lattice.zero(),Strings.create("did:convex:999"),good)));

		// Correct signer identity is insufficient when the signature was tampered with.
		SignedData<AInteger> forged=SignedData.create(ALICE.getAccountKey(),good.getSignature(),
			Ref.get((AInteger)CVMLong.TWO));
		assertEquals(lattice.zero(),lattice.merge(null,entry(lattice.zero(),DID,forged)));

		ALatticeCursor<M> root=Cursors.createLattice(lattice);
		root.setContext(allowAll);
		ALatticeCursor<SignedData<AInteger>> slot=root.path(DID);
		assertNull(slot.merge(bad));
		assertSame(good,slot.merge(good));
		ALatticeCursor<AInteger> value=SignedCursor.forOwner(slot,lattice,DID);
		M before=root.get();
		assertThrows(IllegalStateException.class,() -> value.set(CVMLong.TWO));
		assertSame(before,root.get());

		root.setContext(denyAll);
		value.set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,root.get().get(DID).getValue());
		assertTrue(root.get().get(DID).checkSignature());
		ALatticeCursor<AInteger> fork=value.fork();
		fork.set(CVMLong.create(3));
		assertEquals(CVMLong.TWO,value.get());
		fork.sync();
		assertEquals(CVMLong.create(3),value.get());
		assertTrue(root.get().get(DID).checkSignature());
		assertEquals(DID,lattice.resolveKey(DID));
	}

	@Test
	public void testSynthesisUsesBoundRule() {
		checkSynthesis(OwnerMapLattice.create(SetLattice.create(),ALICE_RULE));
		checkSynthesis(OwnerIndexLattice.create(SetLattice.create(),ALICE_RULE));
	}

	private <M extends AMap<AString,SignedData<ASet<CVMLong>>>> void checkSynthesis(
			AOwnerLattice<AString,ASet<CVMLong>,M> lattice) {
		M own=entry(lattice.zero(),DID,ALICE.signData(Sets.of(1)));
		M other=entry(lattice.zero(),DID,ALICE.signData(Sets.of(2)));
		assertSame(own,lattice.merge(own,other)); // A relay cannot author the union.
		assertSame(own,lattice.merge(LatticeContext.create(null,BOB,(o,k) -> true),own,other));
		M combined=lattice.merge(LatticeContext.create(null,ALICE,(o,k) -> false),own,other);
		assertEquals(Sets.of(1,2),combined.get(DID).getValue());
		assertEquals(ALICE.getAccountKey(),combined.get(DID).getAccountKey());
		assertTrue(combined.get(DID).checkSignature());
	}

	@Test
	public void testSameOwnerDifferentPathRules() {
		Keyword left=Keyword.intern("left");
		Keyword right=Keyword.intern("right");
		var aliceRegion=OwnerMapLattice.create(MaxLattice.INSTANCE,ALICE_RULE);
		var bobRegion=OwnerIndexLattice.<AString,AInteger>create(MaxLattice.INSTANCE,
			(owner,key) -> DID.equals(owner) && BOB.getAccountKey().equals(key));
		var root=Cursors.createLattice(KeyedLattice.create(left,aliceRegion,right,bobRegion));
		root.setContext(LatticeContext.create(null,ALICE));
		ALatticeCursor<AInteger> alice=SignedCursor.forOwner(root.path(left,DID),aliceRegion,DID);
		ALatticeCursor<AInteger> bob=SignedCursor.forOwner(root.path(right,DID),bobRegion,DID);
		alice.set(CVMLong.ONE);
		assertThrows(IllegalStateException.class,() -> bob.set(CVMLong.ONE));
		var before=root.get();
		bob.setContext(LatticeContext.create(null,BOB));
		bob.set(CVMLong.TWO);
		assertEquals(CVMLong.ONE,alice.get());
		assertEquals(CVMLong.TWO,bob.get());
		assertNotEquals(before,root.get());
	}

	@Test
	public void testPublicKeyIndex() {
		var lattice=OwnerKeyLattice.create(MaxLattice.INSTANCE);
		AccountKey owner=ALICE.getAccountKey();
		Index<AccountKey,SignedData<AInteger>> good=Index.of(owner,ALICE.signData((AInteger)CVMLong.ONE));
		Index<AccountKey,SignedData<AInteger>> bad=Index.of(owner,BOB.signData((AInteger)CVMLong.TWO));
		assertEquals(good,lattice.merge(null,good));
		assertEquals(lattice.zero(),lattice.merge(null,bad));
		assertSame(good,lattice.merge(LatticeContext.create(null,BOB,(o,k) -> true),good,bad));
		assertEquals(owner,lattice.resolveKey(Strings.create(owner.toHexString())));
		assertNull(lattice.resolveKey(DID));
		assertNull(lattice.path(Blob.EMPTY));
		assertNull(lattice.path(DID));

		// Decoding an account key can yield its ordinary blob representation.
		Blob encodedKey=Blob.fromHex(owner.toHexString());
		assertEquals(owner,lattice.resolveKey(encodedKey));
		var root=Cursors.createLattice(lattice);
		root.setContext(LatticeContext.create(null,ALICE));
		SignedCursor.forOwner(root.path(encodedKey),lattice,encodedKey).set(CVMLong.ONE);
		assertEquals(good,root.get());
		root.setContext(LatticeContext.create(null,BOB));
		assertThrows(IllegalStateException.class,
			() -> SignedCursor.forOwner(root.path(owner),lattice,owner).set(CVMLong.TWO));
	}

	@Test
	@SuppressWarnings({"rawtypes","unchecked"})
	public void testForeignKeyAndContainerTypes() {
		var keys=OwnerKeyLattice.create(MaxLattice.INSTANCE);
		SignedData<AInteger> signed=ALICE.signData(CVMLong.ONE);
		Index wrongKey=Index.of(Blob.EMPTY,signed);
		assertEquals(keys.zero(),keys.merge(null,wrongKey));
		var didIndex=OwnerIndexLattice.create(MaxLattice.INSTANCE,ALICE_RULE);
		Index wrongDIDType=Index.of(ALICE.getAccountKey(),signed);
		assertEquals(didIndex.zero(),didIndex.merge(null,wrongDIDType));
		assertNull(didIndex.path(CVMLong.ONE));
		AOwnerLattice raw=didIndex;
		assertThrows(ClassCastException.class,() -> raw.merge(null,convex.core.data.Maps.of(DID,signed)));
		ALatticeCursor root=Cursors.createLattice(didIndex);
		ACell before=root.get();
		assertThrows(ClassCastException.class,() -> root.merge(Index.of(DID,CVMLong.ONE)));
		assertSame(before,root.get(),"Malformed values must not partially update the root");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testDecodedKeyIndex() throws Exception {
		var lattice=OwnerKeyLattice.create(MaxLattice.INSTANCE);
		Index<AccountKey,SignedData<AInteger>> value=Index.of(
			ALICE.getAccountKey(),ALICE.signData((AInteger)CVMLong.ONE));
		Index<AccountKey,SignedData<AInteger>> decoded=(Index<AccountKey,SignedData<AInteger>>)
			CVMEncoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(value,true));
		ACell wireKey=decoded.entryAt(0).getKey();
		assertInstanceOf(Blob.class,wireKey);
		assertFalse(wireKey instanceof AccountKey);
		AccountKey owner=lattice.resolveKey(wireKey);
		assertEquals(ALICE.getAccountKey(),owner);
		var merged=lattice.merge(null,decoded);
		assertEquals(value,merged);
		AccountKey mergedOwner=lattice.resolveKey(merged.entryAt(0).getKey());
		assertEquals(owner,mergedOwner);
		assertSame(merged,lattice.merge(merged,decoded));
		var root=Cursors.createLattice(lattice,decoded,LatticeContext.create(null,ALICE));
		SignedCursor.forOwner(root.path(ALICE.getAccountKey()),lattice,ALICE.getAccountKey()).set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,root.get().get(ALICE.getAccountKey()).getValue());
		assertTrue(root.get().get(ALICE.getAccountKey()).checkSignature());
		// Key blobs still cannot impersonate a different signing key.
		Index<AccountKey,SignedData<AInteger>> forged=Index.of(owner,BOB.signData((AInteger)CVMLong.TWO));
		Index<AccountKey,SignedData<AInteger>> decodedForgery=(Index<AccountKey,SignedData<AInteger>>)
			CVMEncoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(forged,true));
		assertSame(merged,lattice.merge(merged,decodedForgery));
	}

	@Test
	public void testNestedOwnershipBoundaries() {
		var inner=OwnerKeyLattice.create(MaxLattice.INSTANCE);
		var outer=OwnerKeyLattice.create(inner);
		var root=Cursors.createLattice(outer);
		root.setContext(LatticeContext.create(null,BOB));
		// Each owner level wraps its own boundary: outer rule first, then the inner one
		ALatticeCursor<Index<AccountKey,SignedData<AInteger>>> aliceValue=
			SignedCursor.forOwner(root.path(ALICE.getAccountKey()),outer,ALICE.getAccountKey());
		ALatticeCursor<AInteger> value=
			SignedCursor.forOwner(aliceValue.path(BOB.getAccountKey()),inner,BOB.getAccountKey());
		assertThrows(IllegalStateException.class,() -> value.set(CVMLong.ONE));
		assertEquals(outer.zero(),root.get(),"Inner authority must not waive the outer rule");
		root.setContext(new LatticeContext() {
			@Override public <T extends ACell> SignedData<T> sign(AccountKey key,T data) {
				if (ALICE.getAccountKey().equals(key)) return ALICE.signData(data);
				if (BOB.getAccountKey().equals(key)) return BOB.signData(data);
				return null;
			}
		});
		value.set(CVMLong.ONE);
		assertEquals(CVMLong.ONE,value.get());
		assertEquals(root.get(),outer.merge(null,root.get()));
		assertTrue(root.get().get(ALICE.getAccountKey()).checkSignature());
		assertTrue(root.get().get(ALICE.getAccountKey()).getValue().get(BOB.getAccountKey()).checkSignature());
	}

	@Test
	public void testConcurrentSignedFieldUpdates() throws Exception {
		var lattice=OwnerIndexLattice.create(MapLattice.<Keyword,AInteger>create(MaxLattice.INSTANCE),ALICE_RULE);
		var root=Cursors.createLattice(lattice);
		root.setContext(LatticeContext.create(null,ALICE));
		Keyword left=Keyword.intern("left");
		Keyword right=Keyword.intern("right");
		ALatticeCursor<AHashMap<Keyword,AInteger>> record=SignedCursor.forOwner(root.path(DID),lattice,DID);
		ALatticeCursor<AInteger> a=record.path(left);
		ALatticeCursor<AInteger> b=record.path(right);
		CountDownLatch entered=new CountDownLatch(2);
		try (var executor=Executors.newFixedThreadPool(2)) {
			var one=executor.submit(() -> concurrentWrite(a,entered));
			var two=executor.submit(() -> concurrentWrite(b,entered));
			one.get(10,TimeUnit.SECONDS);
			two.get(10,TimeUnit.SECONDS);
		}
		assertEquals(CVMLong.ONE,a.get());
		assertEquals(CVMLong.ONE,b.get());
		assertTrue(root.get().get(DID).checkSignature());
	}

	private static void concurrentWrite(ALatticeCursor<AInteger> cursor,CountDownLatch entered) {
		AtomicBoolean first=new AtomicBoolean(true);
		cursor.updateAndGet(previous -> {
			if (first.getAndSet(false)) {
				entered.countDown();
				try {
					assertTrue(entered.await(5,TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
			}
			return CVMLong.ONE;
		});
	}

	@Test
	public void testCustomIndexKeyAndDelegate() {
		Keyword owner=Keyword.intern("team");
		OwnerIndexLattice<Keyword,AInteger> lattice=OwnerIndexLattice.create(MaxLattice.INSTANCE,
			(key,signer) -> owner.equals(key) && BOB.getAccountKey().equals(signer));
		var root=Cursors.createLattice(lattice);
		root.setContext(LatticeContext.create(null,BOB,(o,k) -> false));
		SignedCursor.forOwner(root.path(owner),lattice,owner).set(CVMLong.ONE);
		assertEquals(CVMLong.ONE,root.get().get(owner).getValue());
		assertEquals(root.get(),lattice.merge(null,root.get()));

		// A custom policy can authorise a delegate even for a public-key owner.
		var delegated=OwnerMapLattice.<AccountKey,AInteger>create(MaxLattice.INSTANCE,
			(key,signer) -> ALICE.getAccountKey().equals(key) && BOB.getAccountKey().equals(signer));
		var delegatedRoot=Cursors.createLattice(delegated);
		delegatedRoot.setContext(LatticeContext.create(null,BOB));
		SignedCursor.forOwner(delegatedRoot.path(ALICE.getAccountKey()),delegated,ALICE.getAccountKey()).set(CVMLong.TWO);
		assertEquals(BOB.getAccountKey(),delegatedRoot.get().get(ALICE.getAccountKey()).getAccountKey());
		assertEquals(delegatedRoot.get(),delegated.merge(null,delegatedRoot.get()));
	}

	@SuppressWarnings("unchecked")
	private static <K extends ACell,V extends ACell,M extends AMap<K,SignedData<V>>> M entry(
			M map,K key,SignedData<V> value) {
		return (M)map.assoc(key,value);
	}
}
