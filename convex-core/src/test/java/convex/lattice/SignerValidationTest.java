package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ASet;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;

class SignerValidationTest {

	private static final AKeyPair OWNER=AKeyPair.createSeeded(7430);
	private static final AKeyPair STRANGER=AKeyPair.createSeeded(7431);

	@Test void immediateAndDraftWritesRejectChangedPayloadsAndInvalidSignatures() {
		var rule=OwnerKeyLattice.create(MaxLattice.INSTANCE).signingFor(OWNER.getAccountKey());
		SignedData<AInteger> initial=OWNER.signData(CVMLong.ONE);
		List<Function<AInteger,SignedData<AInteger>>> badSigners=List.of(
			value -> initial,
			value -> SignedData.create(OWNER.getAccountKey(),initial.getSignature(),Ref.get(value)));
		for (var signer:badSigners) {
			var destination=Root.create(initial);
			var immediate=new ASignedComponent<AInteger>(destination,rule,signer) {};
			assertThrows(IllegalArgumentException.class,() -> immediate.cursor().set(CVMLong.TWO));
			assertSame(initial,destination.get());
			var draft=new SignedComponent<>(rule,destination,signer);
			draft.cursor().set(CVMLong.TWO);
			assertThrows(IllegalArgumentException.class,draft::sync);
			assertSame(initial,destination.get());
			assertEquals(CVMLong.TWO,draft.cursor().get(),"failed publication retains the pending edit");
		}
	}

	@Test void missingOrUnauthorisedSignersCannotPublishLocalEdits() {
		var rule=OwnerKeyLattice.create(MaxLattice.INSTANCE).signingFor(OWNER.getAccountKey());
		for (Function<AInteger,SignedData<AInteger>> signer:List.<Function<AInteger,SignedData<AInteger>>>of(
				value -> null,STRANGER::signData)) {
			var destination=new Root<SignedData<AInteger>>();
			var immediate=new ASignedComponent<AInteger>(destination,rule,signer) {};
			assertThrows(IllegalStateException.class,() -> immediate.cursor().set(CVMLong.ONE));
			var draft=new SignedComponent<>(rule,destination,signer);
			draft.cursor().set(CVMLong.ONE);
			assertThrows(IllegalStateException.class,draft::sync);
			assertNull(destination.get());
		}
	}

	@Test void selectionAndNoOpEditsDoNotCallTheSigner() {
		var rule=OwnerKeyLattice.create(SetLattice.<CVMLong>create()).signingFor(OWNER.getAccountKey());
		var first=OWNER.signData(Sets.<CVMLong>of(1));
		SignedData<ASet<CVMLong>> subset=OWNER.signData(Sets.<CVMLong>empty());
		var second=OWNER.signData(Sets.<CVMLong>of(2));
		var destination=Root.create(first);
		var calls=new AtomicInteger();
		var component=new ASignedComponent<ASet<CVMLong>>(destination,rule,value -> {
			calls.incrementAndGet();
			return OWNER.signData(value);
		}) {};
		component.cursor().set(first.getValue());
		component.merge(first);
		component.merge(subset);
		component.merge(STRANGER.signData(Sets.<CVMLong>of(3)));
		assertEquals(0,calls.get());
		assertSame(first,destination.get());
		component.merge(second);
		assertEquals(1,calls.get());
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertTrue(destination.get().checkSignature());
		var union=destination.get();
		component.merge(second);
		assertEquals(1,calls.get());
		assertSame(union,destination.get());
	}

	@Test void synthesisCannotStoreABadSignerResultAndUnavailableSigningKeepsOwn() {
		var rule=OwnerKeyLattice.create(SetLattice.<CVMLong>create()).signingFor(OWNER.getAccountKey());
		var first=OWNER.signData(Sets.<CVMLong>of(1));
		var second=OWNER.signData(Sets.<CVMLong>of(2));
		var destination=Root.create(first);
		var bad=new ASignedComponent<ASet<CVMLong>>(destination,rule,value -> first) {};
		assertThrows(IllegalArgumentException.class,() -> bad.merge(second));
		assertSame(first,destination.get());
		assertSame(first,rule.mergeAndSign(first,second,value -> null));
		assertSame(first,rule.mergeAndSign(first,second,STRANGER::signData));
	}
}
