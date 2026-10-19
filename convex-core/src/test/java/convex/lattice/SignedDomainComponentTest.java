package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SignedLattice;

/** Domain components are constructed at paths and own their signing boundary. */
class SignedDomainComponentTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(7420);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7421);
	private static final OwnerKeyLattice<AInteger> OWNERS=OwnerKeyLattice.create(MaxLattice.INSTANCE);

	private static final class Count extends ASignedComponent<AInteger> {
		Count(ACursor<SignedData<AInteger>> path,SignedLattice<AInteger> rule,Function<AInteger,SignedData<AInteger>> signer) {
			super(path,rule,signer);
		}
		void set(long value) { cursor.set(CVMLong.create(value)); }
	}

	@Test void directPathConstructionIsInertAndViewsShareSignedEdits() {
		var sibling=BOB.signData(CVMLong.TWO);
		var initial=Index.<AccountKey,SignedData<AInteger>>of(BOB.getAccountKey(),sibling);
		var root=Root.create(initial);
		var owner=ALICE.getAccountKey();
		Function<AInteger,SignedData<AInteger>> signer=ALICE::signData;
		var first=new Count(root.path(owner),OWNERS.signingFor(owner),signer);
		var second=new Count(root.path(owner),OWNERS.signingFor(owner),signer);
		assertNull(first.cursor().get());
		assertNull(first.sync());
		assertSame(initial,root.get());
		first.set(10);
		assertEquals(CVMLong.create(10),second.cursor().get());
		assertTrue(root.get().get(owner).checkSignature());
		assertSame(sibling,root.get().get(BOB.getAccountKey()));
		var edited=root.get();
		second.set(10);
		second.sync();
		assertEquals(edited,root.get());
		assertSame(edited.get(owner),root.get().get(owner),"unchanged writes and sync preserve the signed envelope");
	}

	@Test void absentOwnerMapIsCreatedOnlyByTheFirstEditAndPublishesSeparately() {
		var destination=new Root<Index<AccountKey,SignedData<AInteger>>>();
		var map=new KeyOwnedIndex<>(destination,MaxLattice.INSTANCE);
		var owner=ALICE.getAccountKey();
		var count=new Count(map.path(owner),map.getLattice().signingFor(owner),
			ALICE::signData);
		assertNull(count.sync());
		assertNull(map.sync());
		assertNull(destination.get());
		assertNull(map.cursor().get());
		count.set(1);
		count.sync();
		assertTrue(map.get(owner).checkSignature());
		assertNull(destination.get(),"the domain view does not publish its containing map");
		map.sync();
		assertEquals(map.cursor().get(),destination.get());
	}

	@Test void dynamicSignerCannotReplaceTheOwnerRule() {
		var destination=new Root<SignedData<AInteger>>();
		var key=new AtomicReference<AKeyPair>();
		var component=new Count(destination,OWNERS.signingFor(ALICE.getAccountKey()),
			value -> key.get()==null?null:key.get().signData(value));
		assertThrows(IllegalStateException.class,() -> component.set(1));
		key.set(ALICE);
		component.set(1);
		var signed=destination.get();
		key.set(BOB);
		component.setContext(LatticeContext.EMPTY.withSigningKey(ALICE).withOwnerVerifier((o,k) -> true));
		assertThrows(IllegalStateException.class,() -> component.set(2));
		assertSame(signed,destination.get());
	}

	@Test void directEnvelopeDeliveryMatchesTheOwnedMapAndPreservesSignatures() {
		var owner=ALICE.getAccountKey();
		var destination=new Root<SignedData<AInteger>>();
		var component=new Count(destination,OWNERS.signingFor(owner),value -> null);
		var map=new KeyOwnedIndex<>(new Root<>(),MaxLattice.INSTANCE);
		var first=ALICE.signData(CVMLong.ONE);
		var second=ALICE.signData(CVMLong.TWO);
		var forged=SignedData.create(owner,first.getSignature(),Ref.get(CVMLong.create(100)));
		for (ACell incoming:new ACell[] {ALICE.signData(Strings.create("invalid")),first,second,first,
				BOB.signData(CVMLong.create(100)),forged}) {
			component.mergeAt(incoming);
			map.mergeAt(incoming,owner);
			assertEquals(map.get(owner),destination.get());
		}
		assertSame(second,destination.get(),"a replica needs no private key to adopt a valid envelope");
		component.mergeAt(ALICE.signData(CVMLong.create(100)),CVMLong.ZERO);
		assertThrows(IllegalArgumentException.class,() -> component.mergeAt(CVMLong.create(100)));
		assertSame(second,destination.get());
	}
}
