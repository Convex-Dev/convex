package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.social.Feed;
import convex.social.Social;
import convex.social.SocialPost;

/** End-to-end social replication over two in-process P2P nodes. */
public class P2PSocialSyncTest {

	private final AKeyPair aliceKeyPair=AKeyPair.generate();
	private final AKeyPair bobKeyPair=AKeyPair.generate();

	private EtchStore aliceStore;
	private EtchStore bobStore;
	private P2PNode aliceNode;
	private P2PNode bobNode;

	@BeforeEach
	public void setUp() throws Exception {
		aliceStore=EtchStore.createTemp("p2p-social-alice");
		bobStore=EtchStore.createTemp("p2p-social-bob");

		aliceNode=P2PNode.create(aliceStore,NodeConfig.localNetwork(),aliceKeyPair)
			.serveAllInbound();
		bobNode=P2PNode.create(bobStore,NodeConfig.localNetwork(),bobKeyPair)
			.serveAllInbound();
		aliceNode.launch();
		bobNode.launch();

		assertNotEquals(aliceNode.getPort(),bobNode.getPort(),
			"The two nodes must listen on separate OS-assigned ports");

		// Only Alice is told about Bob. Once Bob proves his node key, Alice pushes
		// her own signed NodeInfo record. Bob discovers Alice from that lattice value
		// and establishes the reverse authenticated connection automatically.
		Convex aliceToBob=aliceNode.connect(
			bobKeyPair.getAccountKey(),bobNode.getNodeServer().getHostAddress())
			.get(5,TimeUnit.SECONDS);
		Convex bobToAlice=bobNode.whenConnected(aliceKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);

		assertEquals(bobKeyPair.getAccountKey(),aliceToBob.getVerifiedPeer());
		assertEquals(aliceKeyPair.getAccountKey(),bobToAlice.getVerifiedPeer());
		assertNotNull(bobNode.p2p(aliceKeyPair.getAccountKey()).node().getNodeInfo(),
			"Bob should learn Alice's signed node identity from the bootstrap push");
	}

	@AfterEach
	public void tearDown() throws Exception {
		if (aliceNode!=null) aliceNode.close();
		if (bobNode!=null) bobNode.close();
		if (aliceStore!=null) aliceStore.close();
		if (bobStore!=null) bobStore.close();
	}

	@Test
	public void testTwoUsersSynchroniseSocialFeedsAcrossNodes() throws Exception {
		Social aliceSocial=Social.connect(aliceNode.getApplication());
		Social bobSocial=Social.connect(bobNode.getApplication());

		Feed aliceFeed=aliceSocial.user(aliceKeyPair.getAccountKey()).feed();
		Blob alicePost=aliceFeed.post("Hello from Alice");
		CompletableFuture<Void> bobHasAlice=awaitCondition(bobNode,
			() -> bobSocial.user(aliceKeyPair.getAccountKey()).feed().getPost(alicePost)!=null);
		aliceNode.getApplication().sync();

		bobHasAlice.get(5,TimeUnit.SECONDS);
		var replicatedAlicePost=
			bobSocial.user(aliceKeyPair.getAccountKey()).feed().getPost(alicePost);
		assertNotNull(replicatedAlicePost,"Bob should receive Alice's post");
		assertEquals("Hello from Alice",SocialPost.getText(replicatedAlicePost));

		Feed bobFeed=bobSocial.user(bobKeyPair.getAccountKey()).feed();
		Blob bobPost=bobFeed.post("Hello from Bob");
		CompletableFuture<Void> aliceHasBob=awaitCondition(aliceNode,
			() -> aliceSocial.user(bobKeyPair.getAccountKey()).feed().getPost(bobPost)!=null);
		bobNode.getApplication().sync();

		aliceHasBob.get(5,TimeUnit.SECONDS);
		var replicatedBobPost=
			aliceSocial.user(bobKeyPair.getAccountKey()).feed().getPost(bobPost);
		assertNotNull(replicatedBobPost,"Alice should receive Bob's post");
		assertEquals("Hello from Bob",SocialPost.getText(replicatedBobPost));
		assertEquals(aliceNode.getCursor().get(),bobNode.getCursor().get(),
			"Both nodes should converge after each user publishes once");
	}

	/** Waits only on real root-announcement signals until the expected state exists. */
	private static CompletableFuture<Void> awaitCondition(
			P2PNode node, BooleanSupplier condition) {
		if (condition.getAsBoolean()) return CompletableFuture.completedFuture(null);
		CompletableFuture<ACell> next=node.getNodeServer().getPropagator().nextAnnounce();
		// Close the condition-change-before-future-registration race.
		if (condition.getAsBoolean()) return CompletableFuture.completedFuture(null);
		return next.thenCompose(value -> awaitCondition(node,condition));
	}
}
