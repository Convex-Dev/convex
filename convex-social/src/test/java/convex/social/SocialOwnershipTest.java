package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/** Owner-aware capabilities are shared by every view, independently of authority. */
public class SocialOwnershipTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(8471);
	private static final AKeyPair BOB=AKeyPair.createSeeded(8472);
	private static final AString A=Strings.create("did:convex:101");
	private static final AString B=Strings.create("did:convex:102");

	@Test void oneSignerServesMultipleDIDsAcrossForksTransactionsAndRotation() {
		var keys=new ConcurrentHashMap<ACell,AKeyPair>();
		keys.put(A,ALICE);
		keys.put(B,BOB);
		var time=new AtomicReference<>(CVMLong.create(100));
		DIDKeyAuthorizer authority=(did,key) -> keys.containsKey(did) && keys.get(did).getAccountKey().equals(key);
		var social=Social.create(authority,(owner,value) -> {
			var key=keys.get(owner);
			return key==null?null:key.signData(value);
		},time::get);
		for (var did:List.of(A,B)) social.user(did).feed().post("baseline");
		var aView=social.user(A);
		var fork=social.fork();
		time.set(CVMLong.create(200));
		for (var did:List.of(A,B)) fork.user(did).feed().post("draft");
		time.set(CVMLong.create(300));
		for (var did:List.of(A,B)) social.user(did).feed().post("concurrent");
		fork.sync();
		for (var did:List.of(A,B)) {
			assertEquals(3,social.user(did).feed().count());
			var envelope=social.cursor().get().get(did);
			assertEquals(keys.get(did).getAccountKey(),envelope.getAccountKey());
			assertTrue(envelope.checkSignature());
		}

		// Rotation changes the resolver, not the existing component tree.
		keys.put(A,BOB);
		aView.feed().post("rotated");
		assertEquals(BOB.getAccountKey(),social.cursor().get().get(A).getAccountKey());
		// A transaction view retains the same owner-aware signer and clock.
		time.set(CVMLong.create(400));
		social.transact(draft -> {
			var post=draft.user(B).feed().post("transaction view");
			assertEquals(time.get(),draft.user(B).feed().getPost(post).get(SocialPost.TIMESTAMP));
			assertEquals(3,social.user(B).feed().count());
		});
		assertEquals(4,social.user(B).feed().count());
		keys.remove(A);
		var before=social.cursor().get();
		assertThrows(IllegalStateException.class,() -> aView.feed().post("unavailable"));
		assertSame(before,social.cursor().get());
	}

	@Test void relayAndWrongSignerCannotAuthorButCanSelectExistingEnvelopes() {
		DIDKeyAuthorizer authority=(did,key) -> A.equals(did) && ALICE.getAccountKey().equals(key);
		var left=Social.create(authority,(owner,value) -> ALICE.signData(value),() -> CVMLong.create(100));
		var right=Social.create(authority,(owner,value) -> ALICE.signData(value),() -> CVMLong.create(200));
		left.user(A).feed().post("left");
		right.user(A).feed().post("right");
		var relay=Social.create(authority,null,() -> CVMLong.ZERO);
		relay.merge(left.cursor().get());
		var before=relay.cursor().get();
		relay.merge(right.cursor().get());
		assertSame(before,relay.cursor().get());
		assertThrows(IllegalStateException.class,() -> relay.user(A).feed().post("no authority"));
		var wrong=Social.create(authority,(owner,value) -> BOB.signData(value),() -> CVMLong.ZERO);
		wrong.merge(before);
		wrong.merge(right.cursor().get());
		assertEquals(before,wrong.cursor().get());
		assertThrows(IllegalStateException.class,() -> wrong.user(A).feed().post("wrong key"));
		left.merge(right.cursor().get());
		relay.mergeAt(left.cursor().get().get(A),A);
		assertEquals(2,relay.user(A).feed().count());
		assertSame(left.cursor().get().get(A),relay.cursor().get().get(A));
	}

	@Test void failedSigningDuringReconciliationIsAtomic() {
		var fail=new AtomicBoolean();
		var time=new AtomicReference<>(CVMLong.create(100));
		var social=Social.create((owner,value) -> {
			if (fail.get()) throw new IllegalStateException("Unavailable key service");
			return ALICE.signData(value);
		},time::get);
		var fork=social.fork();
		social.user(ALICE.getAccountKey()).feed().post("live");
		time.set(CVMLong.create(200));
		fork.user(ALICE.getAccountKey()).feed().post("draft");
		var live=social.cursor().get();
		var draft=fork.cursor().get();
		fail.set(true);
		assertThrows(IllegalStateException.class,fork::sync);
		assertSame(live,social.cursor().get());
		assertSame(draft,fork.cursor().get());
		fail.set(false);
		fork.sync();
		assertEquals(2,social.user(ALICE.getAccountKey()).feed().count());
	}
}
