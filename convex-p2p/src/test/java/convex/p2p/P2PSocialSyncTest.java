package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.auth.did.DID;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.etch.EtchStore;
import convex.core.store.MemoryStore;
import convex.node.LatticePropagator;
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;
import convex.social.Social;
import convex.social.SocialLattice;
import convex.social.SocialPost;
import convex.social.SocialUser;

/**
 * End-to-end follow-filtered social replication, independent served views and
 * failure containment, with a fourth node joining late.
 */
public class P2PSocialSyncTest {

	private final AKeyPair aliceNodeKey=AKeyPair.generate();
	private final AKeyPair bobNodeKey=AKeyPair.generate();
	private final AKeyPair carolNodeKey=AKeyPair.generate();
	private final AKeyPair daveNodeKey=AKeyPair.generate();
	private final AKeyPair aliceUserKey=AKeyPair.generate();
	private final AKeyPair bobUserKey=AKeyPair.generate();
	private final AKeyPair carolUserKey=AKeyPair.generate();
	private final AKeyPair daveUserKey=AKeyPair.generate();

	private final AString aliceDid=DID.forKey(aliceUserKey.getAccountKey());
	private final AString bobDid=DID.forKey(bobUserKey.getAccountKey());
	private final AString carolDid=DID.forKey(carolUserKey.getAccountKey());
	private final AString daveDid=DID.forKey(daveUserKey.getAccountKey());

	private EtchStore aliceStore,bobStore,carolStore,daveStore;
	private P2PNode aliceNode,bobNode,carolNode,daveNode;
	private LatticePropagator bobInfrastructureView,bobBrokenView;

	@BeforeEach
	public void setUp() throws Exception {
		aliceStore=EtchStore.createTemp("p2p-social-alice");
		bobStore=EtchStore.createTemp("p2p-social-bob");
		carolStore=EtchStore.createTemp("p2p-social-carol");
		NodeConfig aliceHost=NodeConfig.localNetwork();
		NodeConfig bobHost=NodeConfig.localNetwork();
		NodeConfig carolHost=NodeConfig.localNetwork();
		aliceNode=P2PNode.create(aliceStore,aliceHost,
			LatticePropagatorConfig.create(),aliceNodeKey).serveAllInbound();
		bobNode=P2PNode.create(bobStore,bobHost,
			LatticePropagatorConfig.create(),bobNodeKey).serveAllInbound();
		carolNode=P2PNode.create(carolStore,carolHost,
			LatticePropagatorConfig.create(),carolNodeKey).serveAllInbound();
	}

	@AfterEach
	public void tearDown() throws Exception {
		if (daveNode!=null) daveNode.close();
		if (aliceNode!=null) aliceNode.close();
		if (bobNode!=null) bobNode.close();
		if (carolNode!=null) carolNode.close();
		if (daveStore!=null) daveStore.close();
		if (aliceStore!=null) aliceStore.close();
		if (bobStore!=null) bobStore.close();
		if (carolStore!=null) carolStore.close();
	}

	@Test
	public void testLateJoinerRetainsOnlyDirectFollowSet() throws Exception {
		Social alice=aliceNode.social(aliceDid,aliceUserKey);
		Social bob=bobNode.social(bobDid,bobUserKey);
		Social carol=carolNode.social(carolDid,carolUserKey);
		SocialUser aw=alice.user(aliceDid).fork();
		SocialUser bw=bob.user(bobDid).fork();
		SocialUser cw=carol.user(carolDid).fork();
		Blob alicePost=aw.feed().post("Hello from Alice");
		Blob bobPost=bw.feed().post("Hello from Bob");
		Blob carolPost=cw.feed().post("Hello from Carol");
		aw.follows().follow(bobDid);
		aw.follows().follow(carolDid);
		bw.follows().follow(aliceDid);
		aw.sync();
		bw.sync();
		cw.sync();

		// Bob also publishes an infrastructure-only view from a separate serving
		// store. A third transiently broken view proves failure containment and
		// recovery without replacing the real follow-aware propagation group.
		bobInfrastructureView=new LatticePropagator(new MemoryStore(),P2PLattice.NODE_ROOT,
			value -> value.dissoc(Social.KEY_SOCIAL),LatticePropagatorConfig.create());
		AtomicBoolean failBrokenViewOnce=new AtomicBoolean();
		bobBrokenView=new LatticePropagator(new MemoryStore(),P2PLattice.NODE_ROOT,
			value -> {
				if (failBrokenViewOnce.compareAndSet(false,true)) {
					throw new IllegalStateException("simulated filtered-view failure");
				}
				return value.dissoc(Social.KEY_SOCIAL);
			},LatticePropagatorConfig.create());
		bobNode.getNodeServer().addPropagator(bobInfrastructureView);
		bobNode.getNodeServer().addPropagator(bobBrokenView);
		CompletableFuture<LatticePropagator.Failure> brokenViewFailure=
			bobBrokenView.nextFailure();

		aliceNode.launch();
		bobNode.launch();
		carolNode.launch();
		LatticePropagator.Failure contained=brokenViewFailure.get(5,TimeUnit.SECONDS);
		assertEquals("initial view materialisation",contained.operation());
		assertTrue(bobBrokenView.getStatus().isOperational());
		assertTrue(bobBrokenView.getStatus().hasFailures());
		assertTrue(bobInfrastructureView.getStatus().isOperational());
		assertFalse(bobInfrastructureView.getStatus().hasFailures());
		assertTrue(bobNode.propagationGroup().getStatus().isOperational());
		assertFalse(bobNode.propagationGroup().getStatus().hasFailures());
		@SuppressWarnings("unchecked")
		Index<Keyword,ACell> infrastructure=(Index<Keyword,ACell>)
			bobInfrastructureView.getLastAnnouncedValue();
		assertNull(infrastructure.get(Social.KEY_SOCIAL));
		assertTrue(infrastructure.get(P2PLattice.KEY_P2P)!=null);
		assertEquals(3,Set.of(aliceNode.getPort(),bobNode.getPort(),carolNode.getPort()).size());

		Convex aliceToBob=aliceNode.connect(bobNodeKey.getAccountKey(),
			bobNode.getNodeServer().getHostAddress()).get(5,TimeUnit.SECONDS);
		Convex carolToBob=carolNode.connect(bobNodeKey.getAccountKey(),
			bobNode.getNodeServer().getHostAddress()).get(5,TimeUnit.SECONDS);
		AConnection bobToAlice=bobNode.whenInboundConnectionUpgraded(
			aliceNodeKey.getAccountKey()).get(5,TimeUnit.SECONDS);
		AConnection bobToCarol=bobNode.whenInboundConnectionUpgraded(
			carolNodeKey.getAccountKey()).get(5,TimeUnit.SECONDS);
		assertEquals(bobNodeKey.getAccountKey(),aliceToBob.getVerifiedPeer());
		assertEquals(bobNodeKey.getAccountKey(),carolToBob.getVerifiedPeer());
		assertEquals(aliceNodeKey.getAccountKey(),bobToAlice.getTrustedKey());
		assertEquals(carolNodeKey.getAccountKey(),bobToCarol.getTrustedKey());

		awaitCondition(aliceNode,() -> knowsAllInitialNodes(aliceNode)).get(5,TimeUnit.SECONDS);
		awaitCondition(carolNode,() -> knowsAllInitialNodes(carolNode)).get(5,TimeUnit.SECONDS);
		aliceNode.whenConnected(carolNodeKey.getAccountKey()).get(5,TimeUnit.SECONDS);
		carolNode.whenConnected(aliceNodeKey.getAccountKey()).get(5,TimeUnit.SECONDS);

		CompletableFuture<Void> aliceReady=awaitCondition(aliceNode,
			() -> hasPost(alice,aliceDid,alicePost)&&hasPost(alice,bobDid,bobPost)
				&&hasPost(alice,carolDid,carolPost)
				&&hasCachedKey(alice,aliceDid,bobDid,bobUserKey)
				&&hasCachedKey(alice,aliceDid,carolDid,carolUserKey));
		CompletableFuture<Void> bobReady=awaitCondition(bobNode,
			() -> hasPost(bob,aliceDid,alicePost)&&hasPost(bob,bobDid,bobPost)
				&&hasCachedKey(bob,bobDid,aliceDid,aliceUserKey));
		aliceNode.getApplication().sync();
		bobNode.getApplication().sync();
		carolNode.getApplication().sync();
		CompletableFuture.allOf(aliceReady,bobReady).get(5,TimeUnit.SECONDS);

		assertMaterialised(alice,Set.of(aliceDid,bobDid,carolDid));
		assertMaterialised(bob,Set.of(aliceDid,bobDid));
		assertMaterialised(carol,Set.of(carolDid));
		assertEquals(Set.of(bobDid,carolDid),alice.user(aliceDid).follows().getActive());
		assertEquals(Set.of(aliceDid),bob.user(bobDid).follows().getActive());
		assertTrue(carol.user(carolDid).follows().getActive().isEmpty());
		assertEquals(bobUserKey.getAccountKey(),
			alice.user(aliceDid).follows().getCachedAccountKey(bobDid));
		assertEquals(carolUserKey.getAccountKey(),
			alice.user(aliceDid).follows().getCachedAccountKey(carolDid));
		assertEquals(aliceUserKey.getAccountKey(),
			bob.user(bobDid).follows().getCachedAccountKey(aliceDid));

		// Dave is outbound-only, is told only about Bob, and follows Carol.
		daveStore=EtchStore.createTemp("p2p-social-dave");
		NodeConfig daveHost=NodeConfig.port(-1);
		daveNode=P2PNode.create(daveStore,daveHost,
			LatticePropagatorConfig.create(),daveNodeKey);
		Social dave=daveNode.social(daveDid,daveUserKey);
		SocialUser dw=dave.user(daveDid).fork();
		Blob davePost=dw.feed().post("Hello from Dave");
		dw.follows().follow(carolDid);
		dw.sync();
		daveNode.launch();
		assertEquals(Vectors.empty(),daveNode.p2p().node().getNodeInfo().get(Keywords.TRANSPORTS));

		Convex daveToBob=daveNode.connect(bobNodeKey.getAccountKey(),
			bobNode.getNodeServer().getHostAddress()).get(5,TimeUnit.SECONDS);
		AConnection bobToDave=bobNode.whenInboundConnectionUpgraded(
			daveNodeKey.getAccountKey()).get(5,TimeUnit.SECONDS);
		assertEquals(bobNodeKey.getAccountKey(),daveToBob.getVerifiedPeer());
		assertEquals(daveNodeKey.getAccountKey(),bobToDave.getTrustedKey());
		assertNull(bobNode.propagationGroup().getConnectionManager()
			.getConnection(daveNodeKey.getAccountKey()));

		awaitCondition(daveNode,() -> knowsAllInitialNodes(daveNode)).get(5,TimeUnit.SECONDS);
		daveNode.whenConnected(carolNodeKey.getAccountKey()).get(5,TimeUnit.SECONDS);
		CompletableFuture<Void> daveHasCarol=awaitCondition(daveNode,
			() -> hasPost(dave,carolDid,carolPost)
				&&hasCachedKey(dave,daveDid,carolDid,carolUserKey));
		carolNode.getApplication().sync();
		daveHasCarol.get(5,TimeUnit.SECONDS);

		assertMaterialised(dave,Set.of(daveDid,carolDid));
		assertTrue(hasPost(dave,daveDid,davePost));
		assertEquals(carolUserKey.getAccountKey(),
			dave.user(daveDid).follows().getCachedAccountKey(carolDid));
		assertFalse(hasPost(dave,bobDid,bobPost));
		assertFalse(hasPost(dave,aliceDid,alicePost));
		assertFalse(hasPost(alice,daveDid,davePost));
		assertFalse(hasPost(bob,daveDid,davePost));
		assertFalse(hasPost(carol,daveDid,davePost));
		assertTrue(bobNode.isRunning(),"a failed secondary view must not stop the node");
		assertEquals(1L,bobBrokenView.getStatus().failureCount());
		@SuppressWarnings("unchecked")
		Index<Keyword,ACell> recoveredView=(Index<Keyword,ACell>)
			bobBrokenView.getLastAnnouncedValue();
		assertTrue(recoveredView!=null,"the failed view should recover on a later update");
		assertNull(recoveredView.get(Social.KEY_SOCIAL));
		@SuppressWarnings("unchecked")
		Index<Keyword,ACell> finalInfrastructure=(Index<Keyword,ACell>)
			bobInfrastructureView.getLastAnnouncedValue();
		assertNull(finalInfrastructure.get(Social.KEY_SOCIAL));

		// A public, unverified connection may offer complete data, but possession
		// of an unrelated key cannot mutate Alice's DID-owned feed.
		AKeyPair attackerKey=AKeyPair.generate();
		Blob forgedPost=SocialPost.createKey(Long.MAX_VALUE-1);
		Index<Blob,ACell> forgedFeed=Index.<Blob,ACell>none().assoc(forgedPost,
			SocialPost.createPost("forged",Long.MAX_VALUE-1));
		Index<Keyword,ACell> forgedState=Index.<Keyword,ACell>none().assoc(
			SocialLattice.KEY_FEED,forgedFeed);
		SignedData<Index<Keyword,ACell>> forgedSigned=attackerKey.signData(forgedState);
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> forgedOwners=Maps.of(
			aliceDid,forgedSigned);
		Message forgedUpdate=Message.create(MessageType.LATTICE_VALUE,Vectors.create(
			MessageTag.LATTICE_VALUE,null,Vectors.of(Social.KEY_SOCIAL),forgedOwners));
		try (Convex attacker=Convex.connect(aliceNode.getNodeServer().getHostAddress(),
				null,attackerKey)) {
			Result rejected=attacker.request(forgedUpdate).get(5,TimeUnit.SECONDS);
			assertTrue(rejected.isError());
		}
		assertNull(alice.user(aliceDid).feed().getPost(forgedPost));
	}

	private void assertMaterialised(Social social,Set<AString> expected) {
		for (AString did:Set.of(aliceDid,bobDid,carolDid,daveDid)) {
			boolean present=social.cursor().get()!=null && social.cursor().get().get(did)!=null;
			assertEquals(expected.contains(did),present,"Unexpected materialisation for "+did);
		}
	}

	private static boolean hasPost(Social social,AString did,Blob post) {
		return social.user(did).feed().getPost(post)!=null;
	}

	private static boolean hasCachedKey(Social social,AString localDid,
			AString targetDid,AKeyPair targetKey) {
		var cached=social.user(localDid).follows().getCachedAccountKey(targetDid);
		return cached!=null && targetKey.getAccountKey().equals(cached);
	}

	private boolean knowsAllInitialNodes(P2PNode node) {
		return node.p2p(aliceNodeKey.getAccountKey()).node().getNodeInfo()!=null
			&&node.p2p(bobNodeKey.getAccountKey()).node().getNodeInfo()!=null
			&&node.p2p(carolNodeKey.getAccountKey()).node().getNodeInfo()!=null;
	}

	/** Waits on real root-announcement signals, never elapsed time. */
	private static CompletableFuture<Void> awaitCondition(P2PNode node,BooleanSupplier condition) {
		if (condition.getAsBoolean()) return CompletableFuture.completedFuture(null);
		CompletableFuture<ACell> next=node.propagationGroup().nextAnnounce();
		if (condition.getAsBoolean()) return CompletableFuture.completedFuture(null);
		return next.thenCompose(value -> awaitCondition(node,condition));
	}
}
