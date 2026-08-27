package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import convex.social.Follows;
import convex.social.Social;
import convex.social.SocialPost;
import convex.social.SocialUser;

/** End-to-end social graph replication with a fourth node joining late. */
public class P2PSocialSyncTest {

	private final AKeyPair aliceNodeKeyPair=AKeyPair.generate();
	private final AKeyPair bobNodeKeyPair=AKeyPair.generate();
	private final AKeyPair carolNodeKeyPair=AKeyPair.generate();
	private final AKeyPair daveNodeKeyPair=AKeyPair.generate();

	// Application users have distinct signing keys from their transport nodes. The
	// current Social API still identifies users by AccountKey; its documented DID
	// migration can replace these owners without coupling them back to node identity.
	private final AKeyPair aliceUserKeyPair=AKeyPair.generate();
	private final AKeyPair bobUserKeyPair=AKeyPair.generate();
	private final AKeyPair carolUserKeyPair=AKeyPair.generate();
	private final AKeyPair daveUserKeyPair=AKeyPair.generate();

	private EtchStore aliceStore;
	private EtchStore bobStore;
	private EtchStore carolStore;
	private EtchStore daveStore;
	private P2PNode aliceNode;
	private P2PNode bobNode;
	private P2PNode carolNode;
	private P2PNode daveNode;

	@BeforeEach
	public void setUp() throws Exception {
		aliceStore=EtchStore.createTemp("p2p-social-alice");
		bobStore=EtchStore.createTemp("p2p-social-bob");
		carolStore=EtchStore.createTemp("p2p-social-carol");

		aliceNode=P2PNode.create(aliceStore,NodeConfig.localNetwork(),aliceNodeKeyPair)
			.serveAllInbound();
		bobNode=P2PNode.create(bobStore,NodeConfig.localNetwork(),bobNodeKeyPair)
			.serveAllInbound();
		carolNode=P2PNode.create(carolStore,NodeConfig.localNetwork(),carolNodeKeyPair)
			.serveAllInbound();
	}

	private void launchInitialNetwork() throws Exception {
		aliceNode.launch();
		bobNode.launch();
		carolNode.launch();

		assertEquals(3,Set.of(aliceNode.getPort(),bobNode.getPort(),carolNode.getPort()).size(),
			"Each node must listen on a separate OS-assigned port");

		// Bob is the only configured rendezvous point. Alice and Carol each know only
		// Bob; they push their own signed NodeInfo records and Bob connects back.
		Convex aliceToBob=aliceNode.connect(
			bobNodeKeyPair.getAccountKey(),bobNode.getNodeServer().getHostAddress())
			.get(5,TimeUnit.SECONDS);
		Convex bobToAlice=bobNode.whenConnected(aliceNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		Convex carolToBob=carolNode.connect(
			bobNodeKeyPair.getAccountKey(),bobNode.getNodeServer().getHostAddress())
			.get(5,TimeUnit.SECONDS);
		Convex bobToCarol=bobNode.whenConnected(carolNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);

		assertEquals(bobNodeKeyPair.getAccountKey(),aliceToBob.getVerifiedPeer());
		assertEquals(aliceNodeKeyPair.getAccountKey(),bobToAlice.getVerifiedPeer());
		assertEquals(bobNodeKeyPair.getAccountKey(),carolToBob.getVerifiedPeer());
		assertEquals(carolNodeKeyPair.getAccountKey(),bobToCarol.getVerifiedPeer());
		assertNotNull(bobNode.p2p(aliceNodeKeyPair.getAccountKey()).node().getNodeInfo(),
			"Bob should learn Alice's signed node identity from the bootstrap push");
		assertNotNull(bobNode.p2p(carolNodeKeyPair.getAccountKey()).node().getNodeInfo(),
			"Bob should learn Carol's signed node identity from the bootstrap push");

		CompletableFuture<Void> aliceHasRegistry=awaitCondition(aliceNode,
			() -> knowsNode(aliceNode,bobNodeKeyPair)
				&& knowsNode(aliceNode,carolNodeKeyPair));
		CompletableFuture<Void> carolHasRegistry=awaitCondition(carolNode,
			() -> knowsNode(carolNode,aliceNodeKeyPair)
				&& knowsNode(carolNode,bobNodeKeyPair));
		CompletableFuture.allOf(aliceHasRegistry,carolHasRegistry).get(5,TimeUnit.SECONDS);

		// Registry convergence lets the leaves discover each other without another
		// configured endpoint.
		Convex aliceToCarol=aliceNode.whenConnected(carolNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		Convex carolToAlice=carolNode.whenConnected(aliceNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		assertEquals(carolNodeKeyPair.getAccountKey(),aliceToCarol.getVerifiedPeer());
		assertEquals(aliceNodeKeyPair.getAccountKey(),carolToAlice.getVerifiedPeer());
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
	public void testLateJoiningFourthUserSynchronisesFollowGraphAndFeeds() throws Exception {
		Social aliceSocial=Social.connect(aliceNode.getApplication(),aliceUserKeyPair);
		Social bobSocial=Social.connect(bobNode.getApplication(),bobUserKeyPair);
		Social carolSocial=Social.connect(carolNode.getApplication(),carolUserKeyPair);
		SocialUser aliceWork=aliceSocial.user(aliceUserKeyPair.getAccountKey()).fork();
		SocialUser bobWork=bobSocial.user(bobUserKeyPair.getAccountKey()).fork();
		SocialUser carolWork=carolSocial.user(carolUserKeyPair.getAccountKey()).fork();

		assertNotEquals(aliceNodeKeyPair.getAccountKey(),aliceUserKeyPair.getAccountKey());
		assertNotEquals(bobNodeKeyPair.getAccountKey(),bobUserKeyPair.getAccountKey());
		assertNotEquals(carolNodeKeyPair.getAccountKey(),carolUserKeyPair.getAccountKey());

		Feed aliceFeed=aliceWork.feed();
		Blob alicePost=aliceFeed.post("Hello from Alice");
		Feed bobFeed=bobWork.feed();
		Blob bobPost=bobFeed.post("Hello from Bob");
		Feed carolFeed=carolWork.feed();
		Blob carolPost=carolFeed.post("Hello from Carol");

		// A↔B is mutual, A→C is one-way, and B/C have no relationship. This
		// also covers one user following multiple users and one empty follow list.
		aliceWork.follows().follow(bobUserKeyPair.getAccountKey());
		aliceWork.follows().follow(carolUserKeyPair.getAccountKey());
		bobWork.follows().follow(aliceUserKeyPair.getAccountKey());

		// Publish one complete signed value per user, so the network cannot observe
		// an intermediate state between that user's post and follow actions.
		aliceWork.sync();
		bobWork.sync();
		carolWork.sync();
		assertEquals(Set.of(bobUserKeyPair.getAccountKey(),carolUserKeyPair.getAccountKey()),
			aliceSocial.user(aliceUserKeyPair.getAccountKey()).follows().getActive());
		assertEquals(Set.of(aliceUserKeyPair.getAccountKey()),
			bobSocial.user(bobUserKeyPair.getAccountKey()).follows().getActive());
		assertTrue(carolSocial.user(carolUserKeyPair.getAccountKey())
			.follows().getActive().isEmpty());

		// The first announced value for every node now contains one complete signed
		// user state, avoiding publication of intermediate user actions.
		launchInitialNetwork();
		assertEquals(Set.of(bobUserKeyPair.getAccountKey(),carolUserKeyPair.getAccountKey()),
			aliceSocial.user(aliceUserKeyPair.getAccountKey()).follows().getActive());
		assertEquals(Set.of(aliceUserKeyPair.getAccountKey()),
			bobSocial.user(bobUserKeyPair.getAccountKey()).follows().getActive());
		assertTrue(carolSocial.user(carolUserKeyPair.getAccountKey())
			.follows().getActive().isEmpty());

		CompletableFuture<Void> bobConverged=awaitCondition(bobNode,
			() -> hasExpectedSocialState(bobSocial,alicePost,bobPost,carolPost));

		// The leaves publish independently to the rendezvous node. Bob's local
		// changes are included when each incoming value is merged and announced.
		aliceNode.getApplication().sync();
		carolNode.getApplication().sync();
		awaitExpectedSocialState(bobConverged,bobSocial,alicePost,bobPost,carolPost);

		CompletableFuture<Void> aliceConverged=awaitCondition(aliceNode,
			() -> hasExpectedSocialState(aliceSocial,alicePost,bobPost,carolPost));
		CompletableFuture<Void> carolConverged=awaitCondition(carolNode,
			() -> hasExpectedSocialState(carolSocial,alicePost,bobPost,carolPost));

		// Publish Bob's now-complete lattice value back to both leaves. This is a
		// deterministic convergence barrier rather than relying on message timing.
		bobNode.getApplication().sync();

		awaitExpectedSocialState(aliceConverged,aliceSocial,alicePost,bobPost,carolPost);
		awaitExpectedSocialState(carolConverged,carolSocial,alicePost,bobPost,carolPost);

		assertExpectedSocialState(aliceSocial,alicePost,bobPost,carolPost);
		assertExpectedSocialState(bobSocial,alicePost,bobPost,carolPost);
		assertExpectedSocialState(carolSocial,alicePost,bobPost,carolPost);
		assertEquals(aliceNode.getCursor().get(),bobNode.getCursor().get(),
			"Alice and Bob should converge");
		assertEquals(aliceNode.getCursor().get(),carolNode.getCursor().get(),
			"Alice and Carol should converge");

		// Dave is created and launched only after the original three-node network has
		// converged. His local user value exists before joining, so one bootstrap must
		// merge existing remote state without discarding his independently signed state.
		daveStore=EtchStore.createTemp("p2p-social-dave");
		daveNode=P2PNode.create(daveStore,NodeConfig.localNetwork(),daveNodeKeyPair)
			.serveAllInbound();
		Social daveSocial=Social.connect(daveNode.getApplication(),daveUserKeyPair);
		SocialUser daveWork=daveSocial.user(daveUserKeyPair.getAccountKey()).fork();
		Blob davePost=daveWork.feed().post("Hello from Dave");
		daveWork.follows().follow(carolUserKeyPair.getAccountKey());
		daveWork.sync();
		daveNode.launch();

		assertNotEquals(daveNodeKeyPair.getAccountKey(),daveUserKeyPair.getAccountKey());
		assertEquals(4,Set.of(aliceNode.getPort(),bobNode.getPort(),carolNode.getPort(),
			daveNode.getPort()).size(),"Dave must use another OS-assigned port");

		CompletableFuture<Void> aliceHasDave=awaitCondition(aliceNode,
			() -> hasExpectedLateJoinState(aliceNode,aliceSocial,
				alicePost,bobPost,carolPost,davePost));
		CompletableFuture<Void> bobHasDave=awaitCondition(bobNode,
			() -> hasExpectedLateJoinState(bobNode,bobSocial,
				alicePost,bobPost,carolPost,davePost));
		CompletableFuture<Void> carolHasDave=awaitCondition(carolNode,
			() -> hasExpectedLateJoinState(carolNode,carolSocial,
				alicePost,bobPost,carolPost,davePost));
		CompletableFuture<Void> daveConverged=awaitCondition(daveNode,
			() -> hasExpectedLateJoinState(daveNode,daveSocial,
				alicePost,bobPost,carolPost,davePost));

		// Dave is told only about Bob. connect() pushes Dave's signed NodeInfo and
		// pulls Bob's current root, which publishes Dave's merged local+remote state.
		Convex daveToBob=daveNode.connect(
			bobNodeKeyPair.getAccountKey(),bobNode.getNodeServer().getHostAddress())
			.get(5,TimeUnit.SECONDS);
		Convex bobToDave=bobNode.whenConnected(daveNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		assertEquals(bobNodeKeyPair.getAccountKey(),daveToBob.getVerifiedPeer());
		assertEquals(daveNodeKeyPair.getAccountKey(),bobToDave.getVerifiedPeer());
		assertNotNull(bobNode.p2p(daveNodeKeyPair.getAccountKey()).node().getNodeInfo(),
			"Bob should acknowledge Dave's signed node identity before connect completes");
		assertTrue(hasExpectedLateJoinState(daveNode,daveSocial,
			alicePost,bobPost,carolPost,davePost),
			"connect should merge the existing network into Dave before completing");

		CompletableFuture.allOf(aliceHasDave,bobHasDave,carolHasDave,daveConverged)
			.get(5,TimeUnit.SECONDS);
		Convex daveToAlice=daveNode.whenConnected(aliceNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		Convex daveToCarol=daveNode.whenConnected(carolNodeKeyPair.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		assertEquals(aliceNodeKeyPair.getAccountKey(),daveToAlice.getVerifiedPeer());
		assertEquals(carolNodeKeyPair.getAccountKey(),daveToCarol.getVerifiedPeer());

		assertExpectedLateJoinState(aliceSocial,alicePost,bobPost,carolPost,davePost);
		assertExpectedLateJoinState(bobSocial,alicePost,bobPost,carolPost,davePost);
		assertExpectedLateJoinState(carolSocial,alicePost,bobPost,carolPost,davePost);
		assertExpectedLateJoinState(daveSocial,alicePost,bobPost,carolPost,davePost);
		assertEquals(aliceNode.getCursor().get(),bobNode.getCursor().get(),
			"Alice and Bob should retain convergence after Dave joins");
		assertEquals(aliceNode.getCursor().get(),carolNode.getCursor().get(),
			"Alice and Carol should retain convergence after Dave joins");
		assertEquals(aliceNode.getCursor().get(),daveNode.getCursor().get(),
			"Dave should converge with the existing network");
	}

	private boolean hasExpectedLateJoinState(P2PNode node, Social social,
			Blob alicePost, Blob bobPost, Blob carolPost, Blob davePost) {
		return hasExpectedSocialState(social,alicePost,bobPost,carolPost)
			&& social.user(daveUserKeyPair.getAccountKey()).feed().getPost(davePost)!=null
			&& Set.of(carolUserKeyPair.getAccountKey()).equals(
				social.user(daveUserKeyPair.getAccountKey()).follows().getActive())
			&& knowsNode(node,aliceNodeKeyPair)
			&& knowsNode(node,bobNodeKeyPair)
			&& knowsNode(node,carolNodeKeyPair)
			&& knowsNode(node,daveNodeKeyPair);
	}

	private boolean hasExpectedSocialState(
			Social social, Blob alicePost, Blob bobPost, Blob carolPost) {
		if (social.user(aliceUserKeyPair.getAccountKey()).feed().getPost(alicePost)==null) return false;
		if (social.user(bobUserKeyPair.getAccountKey()).feed().getPost(bobPost)==null) return false;
		if (social.user(carolUserKeyPair.getAccountKey()).feed().getPost(carolPost)==null) return false;
		if (!Set.of(bobUserKeyPair.getAccountKey(),carolUserKeyPair.getAccountKey()).equals(
				social.user(aliceUserKeyPair.getAccountKey()).follows().getActive())) return false;
		if (!Set.of(aliceUserKeyPair.getAccountKey()).equals(
				social.user(bobUserKeyPair.getAccountKey()).follows().getActive())) return false;
		return social.user(carolUserKeyPair.getAccountKey()).follows().getActive().isEmpty();
	}

	private void awaitExpectedSocialState(CompletableFuture<Void> future,
			Social social, Blob alicePost, Blob bobPost, Blob carolPost) throws Exception {
		try {
			future.get(5,TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new AssertionError("Timed out with "+describeSocialState(
				social,alicePost,bobPost,carolPost),e);
		}
	}

	private String describeSocialState(
			Social social, Blob alicePost, Blob bobPost, Blob carolPost) {
		return "posts [alice="
			+(social.user(aliceUserKeyPair.getAccountKey()).feed().getPost(alicePost)!=null)
			+", bob="+(social.user(bobUserKeyPair.getAccountKey()).feed().getPost(bobPost)!=null)
			+", carol="+(social.user(carolUserKeyPair.getAccountKey()).feed().getPost(carolPost)!=null)
			+"]; follows [alice="+social.user(aliceUserKeyPair.getAccountKey()).follows().getActive()
			+", bob="+social.user(bobUserKeyPair.getAccountKey()).follows().getActive()
			+", carol="+social.user(carolUserKeyPair.getAccountKey()).follows().getActive()+"]";
	}

	private static boolean knowsNode(P2PNode observer, AKeyPair subject) {
		return observer.p2p(subject.getAccountKey()).node().getNodeInfo()!=null;
	}

	private void assertExpectedSocialState(
			Social social, Blob alicePost, Blob bobPost, Blob carolPost) {
		assertEquals("Hello from Alice",SocialPost.getText(
			social.user(aliceUserKeyPair.getAccountKey()).feed().getPost(alicePost)));
		assertEquals("Hello from Bob",SocialPost.getText(
			social.user(bobUserKeyPair.getAccountKey()).feed().getPost(bobPost)));
		assertEquals("Hello from Carol",SocialPost.getText(
			social.user(carolUserKeyPair.getAccountKey()).feed().getPost(carolPost)));

		Follows aliceFollows=social.user(aliceUserKeyPair.getAccountKey()).follows();
		Follows bobFollows=social.user(bobUserKeyPair.getAccountKey()).follows();
		Follows carolFollows=social.user(carolUserKeyPair.getAccountKey()).follows();
		assertEquals(Set.of(bobUserKeyPair.getAccountKey(),carolUserKeyPair.getAccountKey()),
			aliceFollows.getActive());
		assertEquals(Set.of(aliceUserKeyPair.getAccountKey()),bobFollows.getActive());
		assertTrue(carolFollows.getActive().isEmpty());
		assertTrue(aliceFollows.isFollowing(bobUserKeyPair.getAccountKey()));
		assertTrue(aliceFollows.isFollowing(carolUserKeyPair.getAccountKey()));
		assertTrue(bobFollows.isFollowing(aliceUserKeyPair.getAccountKey()));
		assertFalse(carolFollows.isFollowing(aliceUserKeyPair.getAccountKey()));
		assertFalse(bobFollows.isFollowing(carolUserKeyPair.getAccountKey()));
		assertFalse(carolFollows.isFollowing(bobUserKeyPair.getAccountKey()));
	}

	private void assertExpectedLateJoinState(Social social,
			Blob alicePost, Blob bobPost, Blob carolPost, Blob davePost) {
		assertExpectedSocialState(social,alicePost,bobPost,carolPost);
		assertEquals("Hello from Dave",SocialPost.getText(
			social.user(daveUserKeyPair.getAccountKey()).feed().getPost(davePost)));
		Follows daveFollows=social.user(daveUserKeyPair.getAccountKey()).follows();
		assertEquals(Set.of(carolUserKeyPair.getAccountKey()),daveFollows.getActive());
		assertTrue(daveFollows.isFollowing(carolUserKeyPair.getAccountKey()));
		assertFalse(social.user(carolUserKeyPair.getAccountKey()).follows()
			.isFollowing(daveUserKeyPair.getAccountKey()));
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
