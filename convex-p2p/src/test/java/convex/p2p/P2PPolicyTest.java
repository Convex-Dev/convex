package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.social.Social;

/** Policy tests need neither sockets nor temporary stores. */
public class P2PPolicyTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(8481);
	private static final AKeyPair BOB=AKeyPair.createSeeded(8482);

	@Test void multiKeySignerAndExplicitNodeIdentityAreIndependentOfContext() {
		var keys=Map.of(ALICE.getAccountKey(),ALICE,BOB.getAccountKey(),BOB);
		var signatures=new AtomicInteger();
		var host=LatticeHost.create();
		var app=host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host,ALICE.getAccountKey(),
			(owner,value) -> {
				signatures.incrementAndGet();
				var key=keys.get(owner);
				return key==null?null:key.signData(value);
			},() -> CVMLong.create(123),DIDKeyAuthorizer.CONVEX));
		app.setContext(LatticeContext.create(CVMLong.ZERO,BOB,(owner,key) -> true));
		for (var key:keys.keySet()) app.identity(key).setIdentity(Strings.create("identity"),null,100);
		app.directory().publishOwnRecord(null);
		assertEquals(CVMLong.create(123),app.node(ALICE.getAccountKey()).getNodeInfo().get(P2PLattice.ID_TIMESTAMP));
		assertNull(app.node(BOB.getAccountKey()).getNodeInfo());
		assertEquals(3,signatures.get());
		// LWW relay selection never invokes a signer, even with local capability.
		var incoming=P2PLattice.createSignedIdentity(BOB,P2PLattice.createIdentity(Strings.create("updated"),null,null,200));
		app.mergeAt(incoming,P2PLattice.KEY_ID);
		assertEquals(3,signatures.get());
		assertEquals(Strings.create("updated"),app.identity(BOB.getAccountKey()).getIdentity().get(P2PLattice.ID_NAME));
		// An ambient permissive context cannot replace the registry's bound owner rule.
		var before=app.cursor().get();
		app.mergeAt(Maps.of(ALICE.getAccountKey(),incoming.get(BOB.getAccountKey())),P2PLattice.KEY_ID);
		assertSame(before,app.cursor().get());
	}

	@Test void registeredDIDsShareAnOwnerAwareSocialViewAndOneVerifier() {
		AString a=Strings.create("did:convex:201");
		AString b=Strings.create("did:convex:202");
		var keys=new ConcurrentHashMap<ACell,AKeyPair>();
		keys.put(a,ALICE);
		keys.put(b,BOB);
		DIDKeyAuthorizer authority=(did,key) -> keys.containsKey(did) && keys.get(did).getAccountKey().equals(key);
		var host=LatticeHost.create();
		var app=host.addApplication(P2PApplication.connect(P2PLattice.NODE_ROOT,host,null,null,
			() -> CVMLong.create(100),authority));
		var social=app.social(a,ALICE);
		assertSame(social,app.social(b,BOB));
		var aView=social.user(a);
		aView.feed().post("Alice");
		social.user(b).feed().post("Bob");
		assertEquals(ALICE.getAccountKey(),social.cursor().get().get(a).getAccountKey());
		assertEquals(BOB.getAccountKey(),social.cursor().get().get(b).getAccountKey());
		var old=social.cursor().get().get(a);
		keys.put(a,BOB);
		app.social(a,BOB);
		aView.feed().post("rotated");
		assertEquals(BOB.getAccountKey(),social.cursor().get().get(a).getAccountKey());
		var before=app.cursor().get();
		app.mergeAt(old,Social.KEY_SOCIAL,a);
		assertSame(before,app.cursor().get());
	}

	@Test void relayPreservesUnknownFieldsAndRechecksCurrentAuthority() {
		AString did=Strings.create("did:convex:203");
		var key=new java.util.concurrent.atomic.AtomicReference<>(ALICE.getAccountKey());
		DIDKeyAuthorizer authority=(owner,signer) -> did.equals(owner) && signer.equals(key.get());
		var host=LatticeHost.create();
		var app=host.addApplication(P2PApplication.connect(P2PLattice.NODE_ROOT,host,null,null,
			() -> CVMLong.ZERO,authority));
		app.pinSocial(did);
		var value=convex.core.data.Index.of(convex.core.data.Keyword.intern("future-field"),CVMLong.ONE);
		var signed=ALICE.signData(value);
		app.mergeAt(signed,Social.KEY_SOCIAL,did);
		assertSame(signed,app.cursor().get(Social.KEY_SOCIAL,did));
		var root=app.cursor().get();
		assertEquals(Maps.of(did,signed),app.policy().projectRoot(root).get(Social.KEY_SOCIAL));
		key.set(BOB.getAccountKey());
		assertEquals(Maps.empty(),app.policy().projectRoot(root).get(Social.KEY_SOCIAL),
			"A previously validated signer must not bypass the current authority rule");
		assertSame(root,app.cursor().get(),"Revocation does not retroactively delete accepted state");
	}
}
