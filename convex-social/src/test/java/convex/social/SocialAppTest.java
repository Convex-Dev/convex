package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Tests for the cursor-based Social application API.
 */
public class SocialAppTest {

	@Test
	public void testStandalonePostAndRead() {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);

		Feed feed = social.user(kp.getAccountKey()).feed();
		Blob key = feed.post("Hello, lattice world!");

		assertNotNull(key);
		assertEquals(8, key.count());

		AHashMap<Keyword, ACell> post = feed.getPost(key);
		assertNotNull(post);
		assertEquals("Hello, lattice world!", SocialPost.getText(post));
		assertFalse(SocialPost.isDeleted(post));
		assertEquals(1, feed.count());
	}

	@Test
	public void testMultiplePosts() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);
		Feed feed = social.user(kp.getAccountKey()).feed();

		Blob k1 = feed.post("First");
		Thread.sleep(1); // ensure distinct timestamp keys
		Blob k2 = feed.post("Second");
		Thread.sleep(1);
		Blob k3 = feed.post("Third");

		assertEquals(3, feed.count());
		assertEquals("First", SocialPost.getText(feed.getPost(k1)));
		assertEquals("Second", SocialPost.getText(feed.getPost(k2)));
		assertEquals("Third", SocialPost.getText(feed.getPost(k3)));
	}

	@Test
	public void testReply() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();
		Social social = Social.create(alice);

		Feed aliceFeed = social.user(alice.getAccountKey()).feed();
		Blob parentKey = aliceFeed.post("Original post");

		Feed bobFeed = social.user(bob.getAccountKey()).feed();
		Blob replyKey = bobFeed.reply("Great post!", parentKey, alice.getAccountKey());

		AHashMap<Keyword, ACell> reply = bobFeed.getPost(replyKey);
		assertNotNull(reply);
		assertEquals("Great post!", SocialPost.getText(reply));
		assertEquals(parentKey, reply.get(SocialPost.REPLY_TO));
		assertEquals(alice.getAccountKey(), reply.get(SocialPost.REPLY_DID));
	}

	@Test
	public void testDeletePost() {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);
		Feed feed = social.user(kp.getAccountKey()).feed();

		Blob key = feed.post("To be deleted");
		assertFalse(SocialPost.isDeleted(feed.getPost(key)));

		feed.delete(key);
		assertTrue(SocialPost.isDeleted(feed.getPost(key)));
		// Tombstoned entry still counted
		assertEquals(1, feed.count());
	}

	@Test
	public void testFollowAndUnfollow() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();
		Social social = Social.create(alice);

		Follows follows = social.user(alice.getAccountKey()).follows();

		// Initially not following anyone
		assertTrue(follows.getActive().isEmpty());
		assertFalse(follows.isFollowing(bob.getAccountKey()));

		// Follow Bob
		follows.follow(bob.getAccountKey());
		assertTrue(follows.isFollowing(bob.getAccountKey()));
		assertEquals(Set.of(bob.getAccountKey()), follows.getActive());

		// Unfollow Bob
		follows.unfollow(bob.getAccountKey());
		assertFalse(follows.isFollowing(bob.getAccountKey()));
		assertTrue(follows.getActive().isEmpty());
	}

	@Test
	public void testMultipleFollows() {
		AKeyPair me = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();
		AKeyPair carol = AKeyPair.generate();
		Social social = Social.create(me);

		Follows follows = social.user(me.getAccountKey()).follows();
		follows.follow(bob.getAccountKey());
		follows.follow(carol.getAccountKey());

		Set<AccountKey> active = follows.getActive();
		assertEquals(2, active.size());
		assertTrue(active.contains(bob.getAccountKey()));
		assertTrue(active.contains(carol.getAccountKey()));
	}

	@Test
	public void testForkAndSync() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AccountKey key = kp.getAccountKey();
		Social social = Social.create(kp);

		// Post in the original
		social.user(key).feed().post("Before fork");
		Thread.sleep(1); // ensure distinct timestamp key

		// Fork, post in fork
		Social forked = social.fork();
		Blob forkedKey = forked.user(key).feed().post("In fork");

		// Original should not see the forked post yet
		Feed origFeed = social.user(key).feed();
		assertEquals(1, origFeed.count());

		// Sync merges back
		forked.sync();

		// Now original should see both
		origFeed = social.user(key).feed();
		assertEquals(2, origFeed.count());
		assertNotNull(origFeed.getPost(forkedKey));
	}

	@Test
	public void testForkAndSyncMultipleUsers() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();
		Social social = Social.create(alice);

		// Alice posts
		social.user(alice.getAccountKey()).feed().post("Alice original");

		// Fork: Bob posts
		Social forked = social.fork();
		forked.user(bob.getAccountKey()).feed().post("Bob in fork");

		forked.sync();

		// Both users' posts visible in original
		assertEquals(1, social.user(alice.getAccountKey()).feed().count());
		assertEquals(1, social.user(bob.getAccountKey()).feed().count());
	}

	@Test
	public void testConnectToRootCursor() {
		AKeyPair kp = AKeyPair.generate();

		// Simulate a root lattice with :social
		KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
		ALatticeCursor<?> rootCursor = Cursors.createLattice(root);

		Social social = Social.connect(rootCursor, kp);

		Feed feed = social.user(kp.getAccountKey()).feed();
		Blob key = feed.post("Connected post");

		assertNotNull(key);
		assertEquals("Connected post", SocialPost.getText(feed.getPost(key)));
		assertEquals(1, feed.count());
	}

	@Test
	public void testConnectWritesPropagateToRoot() {
		AKeyPair kp = AKeyPair.generate();

		KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
		ALatticeCursor<?> rootCursor = Cursors.createLattice(root);

		Social social = Social.connect(rootCursor, kp);
		social.user(kp.getAccountKey()).feed().post("Propagated");

		// Root cursor should have the data
		ACell rootValue = rootCursor.get();
		assertNotNull(rootValue, "Root cursor should contain data after post");
	}

	@Test
	public void testFeedAuthor() {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);
		Feed feed = social.user(kp.getAccountKey()).feed();
		assertEquals(kp.getAccountKey(), feed.getAuthor());
	}

	@Test
	public void testEmptyFeed() {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);
		Feed feed = social.user(kp.getAccountKey()).feed();

		assertEquals(0, feed.count());
		assertNull(feed.getPost(SocialPost.createKey(1000L)));
	}

	@Test
	public void testCursorAccessors() {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);

		assertNotNull(social.cursor());

		SocialUser user = social.user(kp.getAccountKey());
		assertNotNull(user.cursor());
		assertEquals(kp.getAccountKey(), user.getOwnerKey());

		assertNotNull(user.feed().cursor());
		assertNotNull(user.follows().cursor());
	}

	// ===== Adversarial: posting to another user's feed =====

	/**
	 * Core adversarial test: forgery is rejected at the OwnerLattice merge
	 * boundary (the network merge point between nodes).
	 *
	 * Attack: Alice signs data and places it under Bob's owner key.
	 * Defence: OwnerLattice.merge(context, ...) checks verifyOwner(ownerKey,
	 * signerKey) — rejects because Alice's key != Bob's key.
	 */
	@Test
	public void testForgeryRejectedAtOwnerLatticeMerge() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();

		// Build legitimate state: Alice posts to her own feed
		AHashMap<Keyword, ACell> alicePost = SocialPost.createPost("Alice legit", 1000L);
		Index<Blob, ACell> aliceFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), alicePost);
		Index<Keyword, ACell> aliceState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, aliceFeed);
		SignedData<Index<Keyword, ACell>> aliceSigned = alice.signData(aliceState);

		// Build forged state: Alice signs a post but places it under Bob's key
		AHashMap<Keyword, ACell> forgedPost = SocialPost.createPost("Forged by Alice!", 2000L);
		Index<Blob, ACell> forgedFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(2000L), forgedPost);
		Index<Keyword, ACell> forgedState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, forgedFeed);
		// Signed by Alice — but placed under Bob's owner key
		SignedData<Index<Keyword, ACell>> forgedSigned = alice.signData(forgedState);

		// Alice's node: contains Alice's legit data + forgery under Bob's key
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> attackerNode = Maps.of(
			alice.getAccountKey(), aliceSigned,
			bob.getAccountKey(), forgedSigned);  // forgery!

		// Bob's node: contains Bob's legitimate data
		AHashMap<Keyword, ACell> bobPost = SocialPost.createPost("Bob legit", 3000L);
		Index<Blob, ACell> bobFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(3000L), bobPost);
		Index<Keyword, ACell> bobState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, bobFeed);
		SignedData<Index<Keyword, ACell>> bobSigned = bob.signData(bobState);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> honestNode = Maps.of(
			bob.getAccountKey(), bobSigned);

		// Network merge: Bob's node receives Alice's node state
		OwnerLattice<Index<Keyword, ACell>> ownerLattice = Social.SOCIAL_LATTICE;
		LatticeContext ctx = LatticeContext.create(null, bob);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> merged =
			ownerLattice.merge(ctx, honestNode, attackerNode);

		// Alice's legitimate entry survives (correctly signed by Alice under Alice's key)
		SignedData<Index<Keyword, ACell>> aliceResult = merged.get(alice.getAccountKey());
		assertNotNull(aliceResult, "Alice's legitimate entry should survive merge");
		assertEquals(alice.getAccountKey(), aliceResult.getAccountKey());
		assertEquals(1, SocialLattice.getFeed(aliceResult.getValue()).count());

		// Bob's legitimate entry survives (correctly signed by Bob under Bob's key)
		SignedData<Index<Keyword, ACell>> bobResult = merged.get(bob.getAccountKey());
		assertNotNull(bobResult, "Bob's legitimate entry should survive merge");
		assertEquals(bob.getAccountKey(), bobResult.getAccountKey(),
			"Bob's entry should be signed by Bob, not Alice");

		// Bob's feed has only his own post, not the forgery
		Index<Blob, ACell> bobResultFeed = SocialLattice.getFeed(bobResult.getValue());
		assertEquals(1, bobResultFeed.count());
		assertEquals("Bob legit",
			SocialPost.getText((AHashMap<Keyword, ACell>) bobResultFeed.get(SocialPost.createKey(3000L))));
		assertNull(bobResultFeed.get(SocialPost.createKey(2000L)),
			"Forged post (t=2000, signed by Alice) should be rejected under Bob's key");
	}

	/**
	 * Forgery into an empty slot: attacker signs data under a victim's key
	 * when the victim has no existing data. The merge should still reject.
	 */
	@Test
	public void testForgeryIntoEmptySlotRejected() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();

		// Alice forges a post under Bob's key — Bob has no data on the receiving node
		AHashMap<Keyword, ACell> forgedPost = SocialPost.createPost("Forged!", 1000L);
		Index<Blob, ACell> forgedFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), forgedPost);
		Index<Keyword, ACell> forgedState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, forgedFeed);
		SignedData<Index<Keyword, ACell>> forgedSigned = alice.signData(forgedState);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> attackerData = Maps.of(
			bob.getAccountKey(), forgedSigned);

		// Receiving node has nothing
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> emptyNode = Maps.empty();

		OwnerLattice<Index<Keyword, ACell>> ownerLattice = Social.SOCIAL_LATTICE;
		LatticeContext ctx = LatticeContext.create(null, bob);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> merged =
			ownerLattice.merge(ctx, emptyNode, attackerData);

		// Forgery should be rejected — Bob's slot should remain empty
		SignedData<Index<Keyword, ACell>> bobResult = merged.get(bob.getAccountKey());
		assertNull(bobResult,
			"Forgery into empty slot should be rejected: signer (Alice) != owner (Bob)");
	}

	/**
	 * Cursor-level test: Alice can write to Bob's feed locally (local state
	 * is always trusted), but the data is signed by Alice's key — which means
	 * it will be rejected when merged with any other node.
	 */
	@Test
	public void testForgeryVisibleLocallySignedByWrongKey() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();

		Social social = Social.create(alice);

		// Alice posts to Bob's feed — locally succeeds (cursor doesn't check ownership)
		Blob forgedKey = social.user(bob.getAccountKey()).feed().post("Forged!");
		assertEquals(1, social.user(bob.getAccountKey()).feed().count());

		// But the SignedData is signed by Alice, not Bob
		// Extract raw OwnerLattice map and verify the signer
		@SuppressWarnings("unchecked")
		AHashMap<ACell, ACell> ownerMap = (AHashMap<ACell, ACell>) social.cursor().get();
		ACell bobEntry = ownerMap.get(bob.getAccountKey());
		assertNotNull(bobEntry, "Forged entry exists in local state");
		assertTrue(bobEntry instanceof SignedData<?>);

		@SuppressWarnings("unchecked")
		SignedData<Index<Keyword, ACell>> bobSigned = (SignedData<Index<Keyword, ACell>>) bobEntry;
		assertEquals(alice.getAccountKey(), bobSigned.getAccountKey(),
			"Forged entry is signed by Alice (the attacker), not Bob (the victim)");
	}
}
