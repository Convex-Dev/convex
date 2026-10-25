package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/** The same owner rule applies to complete maps, owner paths and local authoring. */
class OwnedComponentsTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(7400);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7401);
	private static final AKeyPair DELEGATE=AKeyPair.createSeeded(7402);
	private static final AString A=Strings.intern("did:example:alice");
	private static final AString B=Strings.intern("did:example:bob");
	private static final AString UNKNOWN=Strings.intern("did:example:unknown");
	private static final BiPredicate<AString,AccountKey> VERIFIER=(owner,signer) ->
		(A.equals(owner) && (ALICE.getAccountKey().equals(signer) || DELEGATE.getAccountKey().equals(signer)))
		|| (B.equals(owner) && BOB.getAccountKey().equals(signer));

	static class Count extends ASignedComponent<AInteger> {
		Count(ACursor<SignedData<AInteger>> destination,SignedLattice<AInteger> rule,Function<AInteger,SignedData<AInteger>> signer) {
			super(destination,rule,signer);
		}
		void set(long value) { cursor().set(CVMLong.create(value)); }
	}

	private static AOwnedMap<AString,AInteger,?> collection(boolean indexed) {
		return indexed
			?new OwnedIndex<AString,AInteger>(new Root<>(),MaxLattice.INSTANCE,VERIFIER)
			:new OwnedMap<AString,AInteger>(new Root<>(),MaxLattice.INSTANCE,VERIFIER);
	}

	private static ACell entry(boolean indexed,ACell owner,ACell value) {
		return indexed?Index.of(owner,value):Maps.of(owner,value);
	}

	private static AOwnedMap<AString,AInteger,?> creationPolicy(Root<ACell> root,boolean indexed,boolean allow) {
		return indexed?new OwnedIndex<AString,AInteger>(root.path(),MaxLattice.INSTANCE,VERIFIER) {
			@Override protected Index<AString,SignedData<AInteger>> createContainer() {
				if (!allow) throw new UnsupportedOperationException("Explicit initialisation required");
				return Index.of(B,BOB.signData(CVMLong.ONE));
			}
		}:new OwnedMap<AString,AInteger>(root.path(),MaxLattice.INSTANCE,VERIFIER) {
			@Override protected AHashMap<AString,SignedData<AInteger>> createContainer() {
				if (!allow) throw new UnsupportedOperationException("Explicit initialisation required");
				return Maps.of(B,BOB.signData(CVMLong.ONE));
			}
		};
	}

	/** Every local authoring route honours the same creation policy without partial writes. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void creationCanBeForbiddenWithoutPreventingReadsOrExistingEdits(boolean indexed) {
		var root=new Root<ACell>();
		var owners=creationPolicy(root,indexed,false);
		var slot=owners.path(A);
		var edits=owners.authoringCursor(A,ALICE::signData);
		var draft=owners.draft(A,ALICE::signData);
		assertNull(owners.get(A));
		assertNull(slot.get());
		assertNull(edits.get());
		slot.set(null);
		draft.sync();
		assertNull(root.get());
		assertThrows(UnsupportedOperationException.class,() -> slot.set(ALICE.signData(CVMLong.ONE)));
		assertThrows(UnsupportedOperationException.class,() -> edits.set(CVMLong.ONE));
		draft.cursor().set(CVMLong.TWO);
		assertThrows(UnsupportedOperationException.class,draft::sync);
		assertNull(root.get());
		// An already open draft can publish after explicit initialisation.
		root.set(indexed?Index.none():Maps.empty());
		draft.sync();
		assertEquals(CVMLong.TWO,owners.get(A).getValue());
		assertTrue(owners.get(A).checkSignature());
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void creationPreservesInitialEntriesAndAccessRejectsIncompatibleContainers(boolean indexed) {
		var root=new Root<ACell>();
		var owners=creationPolicy(root,indexed,true);
		var slot=owners.path(A);
		owners.authoringCursor(A,ALICE::signData).set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,owners.get(A).getValue());
		assertEquals(CVMLong.ONE,owners.get(B).getValue());
		for (ACell bad:List.of(CVMLong.ONE,indexed?Maps.empty():Index.none())) {
			root.set(bad);
			assertThrows(IllegalStateException.class,() -> owners.get(A));
			assertThrows(IllegalStateException.class,slot::get);
			assertThrows(IllegalStateException.class,() -> slot.set(ALICE.signData(CVMLong.ONE)));
			assertSame(bad,root.get());
		}
		// Unexamined sibling values remain opaque, even if not signed envelopes.
		root.set(entry(indexed,B,CVMLong.ONE));
		slot.set(ALICE.signData(CVMLong.TWO));
		assertSame(CVMLong.ONE,((AMap<?,?>)root.get()).get(B));
		assertThrows(ClassCastException.class,() -> owners.get(B));
	}

	/** A retry must preserve the winning owner's entry instead of recreating initial contents. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void concurrentCreationPreservesTheWinningContainer(boolean indexed) {
		var attempts=new AtomicInteger();
		var winner=BOB.signData(CVMLong.create(99));
		var root=new Root<ACell>() {
			@Override public ACell getAndUpdate(UnaryOperator<ACell> update) {
				return super.getAndUpdate(current -> {
					ACell candidate=update.apply(current);
					if (attempts.incrementAndGet()==1) set(entry(indexed,B,winner));
					return candidate;
				});
			}
		};
		var owners=creationPolicy(root,indexed,true);
		owners.authoringCursor(A,ALICE::signData).set(CVMLong.TWO);
		assertEquals(2,attempts.get());
		assertSame(winner,owners.get(B));
		assertEquals(CVMLong.TWO,owners.get(A).getValue());
		assertTrue(owners.get(A).checkSignature());
	}

	@Test void standardAuthoringViewUsesTheSameOwnerRuleWithoutMaterialisingReads() {
		for (boolean indexed:new boolean[] {false,true}) {
			var owners=collection(indexed);
			var edits=owners.authoringCursor(A,DELEGATE::signData);
			assertNull(edits.get());
			assertNull(owners.storage().get());
			var unauthorised=owners.authoringCursor(A,BOB::signData);
			assertThrows(IllegalStateException.class,() -> unauthorised.set(CVMLong.ONE));
			assertNull(owners.storage().get());
			edits.set(CVMLong.ONE);
			var signed=owners.get(A);
			assertEquals(DELEGATE.getAccountKey(),signed.getAccountKey());
			assertTrue(signed.checkSignature());
			owners.mergeAt(CVMLong.TWO); // Wrong containing representation is ignored.
			owners.mergeAt(BOB.signData(CVMLong.TWO),A);
			assertSame(signed,owners.get(A));
			var draft=edits.fork();
			draft.set(CVMLong.TWO);
			assertSame(signed,owners.get(A));
			draft.sync(MaxLattice.INSTANCE::merge);
			assertEquals(CVMLong.TWO,owners.get(A).getValue());
		}
	}

	@Test void bindingIsLiveAndForksRetainOwnershipPolicy() {
		var indexRoot=new Root<Index<AString,SignedData<AInteger>>>();
		var index=new OwnedIndex<>(indexRoot,MaxLattice.INSTANCE,VERIFIER);
		assertSame(indexRoot,index.storage());
		assertForkContract(index,index.fork(),A);
		var mapRoot=new Root<AHashMap<AString,SignedData<AInteger>>>();
		var map=new OwnedMap<>(mapRoot,MaxLattice.INSTANCE,VERIFIER);
		assertSame(mapRoot,map.storage());
		assertForkContract(map,map.fork(),A);
		var keyRoot=new Root<Index<AccountKey,SignedData<AInteger>>>();
		var keys=new KeyOwnedIndex<>(keyRoot,MaxLattice.INSTANCE);
		assertSame(keyRoot,keys.storage());
		assertForkContract(keys,keys.fork(),ALICE.getAccountKey());
	}

	private static <K extends ACell,M extends AMap<K,SignedData<AInteger>>> void assertForkContract(
			AOwnedMap<K,AInteger,M> live,AOwnedMap<K,AInteger,M> draft,K owner) {
		assertSame(live.getLattice(),draft.getLattice(),"Forks retain the exact verification policy");
		assertNull(live.sync());
		draft.mergeAt(BOB.signData(CVMLong.create(10)),owner);
		draft.sync();
		assertNull(live.cursor().get(),"Rejected delivery must not create a collection");
		var first=ALICE.signData(CVMLong.ONE);
		draft.mergeAt(first,owner);
		assertNull(live.cursor().get());
		var second=ALICE.signData(CVMLong.TWO);
		live.mergeAt(second,owner);
		assertSame(second,live.storage().get().get(owner),"Ordinary construction binds live storage");
		draft.sync();
		assertSame(second,draft.get(owner),"Publication reconciles with a concurrent authorised value");
		var before=live.cursor().get();
		draft.sync();
		assertSame(before,live.cursor().get());
	}

	@Test void rootAndOwnerPathDeliveriesHaveIdenticalAuthorisationAndMerge() {
		for (boolean indexed:new boolean[] {false,true}) {
			var root=collection(indexed);
			var path=collection(indexed);
			var first=ALICE.signData(CVMLong.ONE);
			var newer=DELEGATE.signData(CVMLong.TWO);
			var forged=SignedData.create(ALICE.getAccountKey(),first.getSignature(),Ref.get(CVMLong.TWO));
			for (ACell incoming:List.of(first,newer,first,BOB.signData(CVMLong.create(9)),forged,
					ALICE.signData(Strings.create("wrong payload")),CVMLong.ONE)) {
				root.mergeAt(entry(indexed,A,incoming));
				path.mergeAt(incoming,A);
				assertEquals(root.cursor().get(),path.cursor().get());
			}
			assertSame(newer,root.get(A),"accepted envelopes keep their original signature");
			var previous=root.cursor().get();
			root.mergeAt(ALICE.signData(CVMLong.create(10)),UNKNOWN);
			root.mergeAt(CVMLong.create(10),A,CVMLong.ZERO);
			assertSame(previous,root.cursor().get(),"unknown owners and envelope interiors cannot introduce data");
			assertEquals(root.sync(),path.sync());
		}
	}

	@Test void malformedEntryDoesNotPreventIndependentOwnersFromMerging() {
		for (boolean indexed:new boolean[] {false,true}) {
			var map=collection(indexed);
			var valid=BOB.signData(CVMLong.TWO);
			ACell incoming=indexed?Index.of(A,CVMLong.ONE,B,valid):Maps.of(A,CVMLong.ONE,B,valid);
			map.mergeAt(incoming);
			assertNull(map.get(A));
			assertSame(valid,map.get(B));
		}
	}

	@Test void malformedPayloadIsRejectedEvenWhenTheOwnerSlotIsEmpty() {
		for (boolean indexed:new boolean[] {false,true}) {
			var map=collection(indexed);
			map.mergeAt(ALICE.signData(Strings.create("not an integer")),A);
			assertNull(map.get(A));
			map.mergeAt(ALICE.signData(CVMLong.ONE),A);
			assertEquals(CVMLong.ONE,map.get(A).getValue());
		}
	}

	@Test void independentOwnersCommuteAndRepeatedDeliveryIsIdempotent() {
		for (boolean indexed:new boolean[] {false,true}) {
			var forward=collection(indexed);
			var reverse=collection(indexed);
			var a=ALICE.signData(CVMLong.ONE);
			var b=BOB.signData(CVMLong.TWO);
			forward.mergeAt(a,A);
			forward.mergeAt(b,B);
			reverse.mergeAt(b,B);
			reverse.mergeAt(a,A);
			assertEquals(forward.cursor().get(),reverse.cursor().get());
			var result=forward.cursor().get();
			forward.mergeAt(a,A);
			forward.mergeAt(b,B);
			assertSame(result,forward.cursor().get());
		}
	}

	@Test void openingViewsIsInertAndEditsShareOneSignedWorkspace() {
		var destination=new Root<Index<AString,SignedData<AInteger>>>();
		var map=new OwnedIndex<>(destination,MaxLattice.INSTANCE,VERIFIER).fork();
		Function<AInteger,SignedData<AInteger>> alice=ALICE::signData;
		var first=new Count(map.path(A),map.signingFor(A),alice);
		var second=new Count(map.path(A),map.signingFor(A),alice);
		map.draft(A,alice);
		assertNull(map.cursor().get());
		assertNull(map.sync());
		assertNull(destination.get());
		first.set(1);
		assertEquals(CVMLong.ONE,second.cursor().get());
		assertTrue(map.get(A).checkSignature());
		second.sync();
		assertNull(destination.get(),"a lightweight view cannot publish its containing map");
		map.sync();
		assertEquals(map.cursor().get(),destination.get());
	}

	@Test void capabilitiesCannotReplaceTheMapsAuthorisationPolicy() {
		var map=collection(true);
		var view=new Count(map.path(A),map.signingFor(A),BOB::signData);
		assertThrows(IllegalStateException.class,() -> view.set(1));
		assertNull(map.cursor().get());
		var draft=map.draft(A,BOB::signData);
		draft.cursor().set(CVMLong.ONE);
		assertThrows(IllegalStateException.class,draft::sync);
		assertNull(map.cursor().get());
		new Count(map.path(A),map.signingFor(A),DELEGATE::signData).set(2);
		assertEquals(DELEGATE.getAccountKey(),map.get(A).getAccountKey());
		var unknown=new Count(map.path(UNKNOWN),map.signingFor(UNKNOWN),ALICE::signData);
		assertThrows(IllegalStateException.class,() -> unknown.set(3));
		assertNull(map.get(UNKNOWN));
	}

	@Test void independentDraftsPublishOnlyAtTheirOwnBoundaryAndReconcile() {
		var destination=new Root<Index<AccountKey,SignedData<ASet<CVMLong>>>>();
		var map=new KeyOwnedIndex<>(destination,SetLattice.<CVMLong>create()).fork();
		var first=map.draft(ALICE.getAccountKey(),ALICE::signData);
		var second=map.draft(ALICE.getAccountKey(),ALICE::signData);
		first.cursor().set(Sets.of(1));
		second.cursor().set(Sets.of(2));
		map.sync();
		assertNull(destination.get());
		assertNull(map.get(ALICE.getAccountKey()));
		first.sync();
		assertEquals(Sets.of(1),map.get(ALICE.getAccountKey()).getValue());
		assertNull(destination.get());
		second.sync();
		assertEquals(Sets.of(1,2),map.get(ALICE.getAccountKey()).getValue());
		assertTrue(map.get(ALICE.getAccountKey()).checkSignature());
		map.sync();
		assertEquals(map.cursor().get(),destination.get());
	}

	@Test void keyOwnedIndexAcceptsDecodedKeysAndRejectsOtherSigners() {
		var map=new KeyOwnedIndex<>(new Root<>(),MaxLattice.INSTANCE);
		var signed=ALICE.signData(CVMLong.ONE);
		map.mergeAt(Index.of(ALICE.getAccountKey().toBlob(),signed));
		assertSame(signed,map.get(ALICE.getAccountKey()));
		map.mergeAt(BOB.signData(CVMLong.TWO),ALICE.getAccountKey());
		assertSame(signed,map.get(ALICE.getAccountKey()));
	}

	@Test void nonKeyCollectionsRequireAnExplicitVerifier() {
		assertThrows(NullPointerException.class,() -> new OwnedMap<AString,AInteger>(new Root<>(),MaxLattice.INSTANCE,null));
		assertThrows(NullPointerException.class,() -> new OwnedIndex<AString,AInteger>(new Root<>(),MaxLattice.INSTANCE,null));
	}

	@Test void onlyTheSignedDomainComponentCanAuthorASynthesisedValue() {
		var map=new OwnedIndex<AString,ASet<CVMLong>>(new Root<>(),
			SetLattice.create(),VERIFIER);
		var first=ALICE.signData(Sets.<CVMLong>of(1));
		var second=DELEGATE.signData(Sets.<CVMLong>of(2));
		map.mergeAt(first,A);
		map.mergeAt(second,A);
		assertSame(first,map.get(A),"a relay cannot sign a synthesised union");
		var author=new ASignedComponent<ASet<CVMLong>>(map.path(A),map.signingFor(A),
			BOB::signData) {};
		author.merge(second);
		assertSame(first,map.get(A),"the domain component's chosen signer must be authorised");
		var authorised=new ASignedComponent<ASet<CVMLong>>(map.path(A),map.signingFor(A),DELEGATE::signData) {};
		authorised.merge(second);
		assertEquals(Sets.of(1,2),map.get(A).getValue());
		assertEquals(DELEGATE.getAccountKey(),map.get(A).getAccountKey());
		assertTrue(map.get(A).checkSignature());
	}

	@Test void ownedCollectionsNeverResolveCapabilitiesWhenMergingOrPublishing() {
		var indexDestination=new Root<Index<AString,SignedData<ASet<CVMLong>>>>();
		assertPublicMerge(new OwnedIndex<>(indexDestination,SetLattice.<CVMLong>create(),VERIFIER).fork(),indexDestination,A);
		var mapDestination=new Root<AHashMap<AString,SignedData<ASet<CVMLong>>>>();
		assertPublicMerge(new OwnedMap<>(mapDestination,SetLattice.<CVMLong>create(),VERIFIER).fork(),mapDestination,A);
		var keyDestination=new Root<Index<AccountKey,SignedData<ASet<CVMLong>>>>();
		assertPublicMerge(new KeyOwnedIndex<>(keyDestination,SetLattice.<CVMLong>create()).fork(),keyDestination,ALICE.getAccountKey());
	}

	@SuppressWarnings("unchecked")
	private static <K extends ACell,M extends AMap<K,SignedData<ASet<CVMLong>>>> void assertPublicMerge(
			AOwnedMap<K,ASet<CVMLong>,M> map,Root<M> destination,K owner) {
		var first=ALICE.signData(Sets.<CVMLong>of(1));
		var second=ALICE.signData(Sets.<CVMLong>of(2));
		map.mergeAt(first,owner);
		map.merge((M)map.getLattice().zero().assoc(owner,second));
		assertSame(first,map.get(owner),"a newly synthesised union cannot replace a signed envelope");
		map.sync();
		var newer=ALICE.signData(Sets.<CVMLong>of(1,3));
		map.mergeAt(newer,owner);
		assertSame(newer,map.get(owner),"an already signed superset can be adopted");
		destination.set((M)map.getLattice().zero().assoc(owner,second));
		map.sync(); // Both the working copy and destination changed since publication.
		assertSame(newer,destination.get().get(owner),"publication conflicts cannot invoke signing either");
	}

	@Test @SuppressWarnings({"rawtypes","unchecked"})
	void localPayloadsMustAlsoMeetTheValueLatticesValidation() {
		var map=collection(true);
		var view=new Count(map.path(A),map.signingFor(A),ALICE::signData);
		AForkableCursor raw=view.cursor();
		assertThrows(IllegalArgumentException.class,() -> raw.set(Strings.create("not an integer")));
		assertNull(map.cursor().get());
		var draft=map.draft(A,ALICE::signData);
		AForkableCursor draftCursor=draft.cursor();
		draftCursor.set(Strings.create("not an integer"));
		assertThrows(IllegalArgumentException.class,draft::sync);
		assertNull(map.cursor().get());
	}

	@Test void hashMapsSupportNonIndexOwnerIdentities() {
		var destination=new Root<AHashMap<CVMLong,SignedData<AInteger>>>();
		var map=new OwnedMap<>(destination,MaxLattice.INSTANCE,
			(CVMLong owner,AccountKey signer) -> owner.equals(CVMLong.ONE) && signer.equals(ALICE.getAccountKey())).fork();
		map.mergeAt(ALICE.signData(CVMLong.TWO),CVMLong.ONE);
		assertEquals(CVMLong.TWO,map.get(CVMLong.ONE).getValue());
		assertNull(destination.get());
		map.sync();
		assertEquals(map.cursor().get(),destination.get());
	}
}
