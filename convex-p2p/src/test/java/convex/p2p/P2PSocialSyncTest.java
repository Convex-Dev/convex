package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
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
	private Convex aliceToBob;
	private Convex bobToAlice;

	@BeforeEach
	public void setUp() throws Exception {
		aliceStore=EtchStore.createTemp("p2p-social-alice");
		bobStore=EtchStore.createTemp("p2p-social-bob");

		aliceNode=P2PNode.create(aliceStore,NodeConfig.port(0),aliceKeyPair)
			.serveAllInbound();
		bobNode=P2PNode.create(bobStore,NodeConfig.port(0),bobKeyPair)
			.serveAllInbound();
		aliceNode.launch();
		bobNode.launch();

		assertNotEquals(aliceNode.getPort(),bobNode.getPort(),
			"The two nodes must listen on separate OS-assigned ports");

		aliceToBob=ConvexRemote.connect(bobNode.getNodeServer().getHostAddress());
		aliceNode.getNodeServer().getPropagator().addPeer(
			bobKeyPair.getAccountKey(),aliceToBob);

		bobToAlice=ConvexRemote.connect(aliceNode.getNodeServer().getHostAddress());
		bobNode.getNodeServer().getPropagator().addPeer(
			aliceKeyPair.getAccountKey(),bobToAlice);
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
		aliceNode.getApplication().sync();

		// Pull completion covers acquisition, merge and publication of the remote root.
		bobNode.getNodeServer().pull(bobToAlice).get(5,TimeUnit.SECONDS);
		var replicatedAlicePost=
			bobSocial.user(aliceKeyPair.getAccountKey()).feed().getPost(alicePost);
		assertNotNull(replicatedAlicePost,"Bob should receive Alice's post");
		assertEquals("Hello from Alice",SocialPost.getText(replicatedAlicePost));

		Feed bobFeed=bobSocial.user(bobKeyPair.getAccountKey()).feed();
		Blob bobPost=bobFeed.post("Hello from Bob");
		bobNode.getApplication().sync();

		aliceNode.getNodeServer().pull(aliceToBob).get(5,TimeUnit.SECONDS);
		var replicatedBobPost=
			aliceSocial.user(bobKeyPair.getAccountKey()).feed().getPost(bobPost);
		assertNotNull(replicatedBobPost,"Alice should receive Bob's post");
		assertEquals("Hello from Bob",SocialPost.getText(replicatedBobPost));
		assertEquals(aliceNode.getCursor().get(),bobNode.getCursor().get(),
			"Both nodes should converge after each user publishes once");
	}
}
