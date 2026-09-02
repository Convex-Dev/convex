package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.ALatticeComponent;
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
	private static LatticeContext wallet(AKeyPair primary,AKeyPair... additional) {
		return new LatticeContext() {
			@Override public AKeyPair getSigningKey() {
				return primary;
			}

			@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey,T value) {
				if (accountKey==null || accountKey.equals(primary.getAccountKey())) {
					return primary.signData(value);
				}
				for (AKeyPair keyPair:additional) {
					if (accountKey.equals(keyPair.getAccountKey())) return keyPair.signData(value);
				}
				return null;
			}
		};
	}

	private static class TestRoot extends ALatticeComponent<Index<Keyword, ACell>> {

		private int persistCount;

		TestRoot(KeyedLattice lattice) {
			super(Cursors.createLattice(lattice));
		}

		@Override
		protected <T extends ACell> T persist(T value) {
			persistCount++;
			return value;
		}
	}

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

		// No delay between posts: same-millisecond posts must still get
		// distinct keys (Feed bumps the timestamp on collision)
		Blob k1 = feed.post("First");
		Blob k2 = feed.post("Second");
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
		Social social=Social.create(wallet(alice,bob));

		Feed aliceFeed = social.user(alice.getAccountKey()).feed();
		Blob parentKey = aliceFeed.post("Original post");

		Feed bobFeed = social.user(bob.getAccountKey()).feed();
		Blob replyKey = bobFeed.reply("Great post!", parentKey, alice.getAccountKey());

		AHashMap<Keyword, ACell> reply = bobFeed.getPost(replyKey);
		assertNotNull(reply);
		assertEquals("Great post!", SocialPost.getText(reply));
		assertEquals(parentKey, reply.get(SocialPost.REPLY_TO));
		assertEquals(DID.forKey(alice.getAccountKey()), reply.get(SocialPost.REPLY_DID));
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
		Social social=Social.create(alice);

		Follows follows = social.user(alice.getAccountKey()).follows();

		// Initially not following anyone
		assertTrue(follows.getActive().isEmpty());
		assertFalse(follows.isFollowing(bob.getAccountKey()));

		// Follow Bob
		follows.follow(bob.getAccountKey());
		assertTrue(follows.isFollowing(bob.getAccountKey()));
		assertEquals(Set.of(DID.forKey(bob.getAccountKey())), follows.getActive());

		// Unfollow Bob
		follows.unfollow(bob.getAccountKey());
		assertFalse(follows.isFollowing(bob.getAccountKey()));
		assertTrue(follows.getActive().isEmpty());
	}

	@Test
	public void testValidatedFollowKeyCachePreservesIntent() throws Exception {
		AKeyPair alice=AKeyPair.generate();
		AKeyPair bob=AKeyPair.generate();
		AString bobDid=convex.core.data.Strings.create("did:web:bob.example");
		Social social=Social.create(alice);
		Follows follows=social.user(alice.getAccountKey()).follows();

		assertThrows(IllegalStateException.class,
			() -> follows.cacheValidatedKey(bobDid,bob.getAccountKey()));
		follows.follow(bobDid);
		follows.cacheValidatedKey(bobDid,bob.getAccountKey());
		assertTrue(follows.isFollowing(bobDid));
		assertEquals(bob.getAccountKey(),follows.getCachedAccountKey(bobDid));

		SignedData<?> signed=social.cursor().get().get(DID.forKey(alice.getAccountKey()));
		SignedData<?> decoded=(SignedData<?>)CVMEncoder.INSTANCE.decodeMultiCell(
			Format.encodeMultiCell(signed,true));
		@SuppressWarnings("unchecked")
		AHashMap<ACell,ACell> encodedFollows=SocialLattice.getFollows(
			(Index<Keyword,ACell>)decoded.getValue());
		ACell encodedKey=((AHashMap<?,?>)encodedFollows.get(bobDid))
			.get(Follows.KEY_ACCOUNT_KEY);
		assertEquals(bob.getAccountKey(),AccountKey.parse(encodedKey));
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

		Set<AString> active = follows.getActive();
		assertEquals(2, active.size());
		assertTrue(active.contains(DID.forKey(bob.getAccountKey())));
		assertTrue(active.contains(DID.forKey(carol.getAccountKey())));
	}

	@Test
	public void testForkAndSync() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AccountKey key = kp.getAccountKey();
		Social social = Social.create(kp);

		// Post in the original
		social.user(key).feed().post("Before fork");

		// Fork, post in fork (fork copies the feed, so a same-millisecond post
		// gets a bumped key rather than colliding with "Before fork")
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
		Social social=Social.create(wallet(alice,bob));

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
	@SuppressWarnings("unchecked")
	public void testUserForkBatchedValueSurvivesEncodingRoundTrip() throws Exception {
		AKeyPair alice=AKeyPair.generate();
		AKeyPair bob=AKeyPair.generate();
		Social social=Social.create(alice);
		SocialUser work=social.user(alice.getAccountKey()).fork();

		Blob post=work.feed().post("Hello from Alice");
		work.follows().follow(bob.getAccountKey());
		assertEquals(0,social.user(alice.getAccountKey()).feed().count());
		assertTrue(social.user(alice.getAccountKey()).follows().getActive().isEmpty());
		work.sync();

		SignedData<Index<Keyword,ACell>> signed=
			(SignedData<Index<Keyword,ACell>>)social.cursor().get().get(
				DID.forKey(alice.getAccountKey()));
		SignedData<Index<Keyword,ACell>> decoded=
			(SignedData<Index<Keyword,ACell>>)CVMEncoder.INSTANCE.decodeMultiCell(
				Format.encodeMultiCell(signed,true));

		assertTrue(decoded.checkSignature());
		assertNotNull(SocialLattice.getFeed(decoded.getValue()).get(post));
		assertEquals(Set.of(DID.forKey(bob.getAccountKey())),SocialHelpers.getActiveFollows(
			SocialLattice.getFollows(decoded.getValue())));
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
	public void testComponentPersistenceDelegatesToContainingRoot() throws IOException {
		AKeyPair kp=AKeyPair.generate();
		KeyedLattice lattice=Lattice.ROOT.addLattice(Social.KEY_SOCIAL,Social.SOCIAL_LATTICE);
		TestRoot root=new TestRoot(lattice);
		Social social=Social.connect(root,kp);
		Feed feed=social.user(kp.getAccountKey()).feed();
		feed.post("Persist through component parents");

		Index<Blob, ACell> before=feed.cursor().get();
		Index<Blob, ACell> persisted=feed.persist();

		assertSame(before,persisted);
		assertSame(before,feed.cursor().get());
		assertEquals(1,root.persistCount);
	}

	@Test
	public void testComponentConnectionInheritsRootContext() {
		AKeyPair kp=AKeyPair.generate();
		KeyedLattice lattice=Lattice.ROOT.addLattice(Social.KEY_SOCIAL,Social.SOCIAL_LATTICE);
		TestRoot root=new TestRoot(lattice);
		root.cursor().setContext(LatticeContext.create(null,kp));
		Social social=Social.connect(root);

		social.user(kp.getAccountKey()).feed().post("Inherited context");

		assertEquals(1,social.user(kp.getAccountKey()).feed().count());
	}

	@Test
	public void testForkRetainsComponentPersistenceParent() throws IOException {
		AKeyPair kp=AKeyPair.generate();
		KeyedLattice lattice=Lattice.ROOT.addLattice(Social.KEY_SOCIAL,Social.SOCIAL_LATTICE);
		TestRoot root=new TestRoot(lattice);
		Social social=Social.connect(root,kp);
		Social fork=social.fork();
		Feed forkFeed=fork.user(kp.getAccountKey()).feed();
		forkFeed.post("Persisted but not synced");

		forkFeed.persist();

		assertEquals(1,root.persistCount);
		assertEquals(0,social.user(kp.getAccountKey()).feed().count());
	}

	@Test
	public void testFeedAuthor() {
		AKeyPair kp = AKeyPair.generate();
		Social social = Social.create(kp);
		Feed feed = social.user(kp.getAccountKey()).feed();
		assertEquals(DID.forKey(kp.getAccountKey()), feed.getAuthor());
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
		AString aliceDid=DID.forKey(alice.getAccountKey());
		AString bobDid=DID.forKey(bob.getAccountKey());
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> attackerNode = Maps.of(
			aliceDid, aliceSigned,
			bobDid, forgedSigned);  // forgery!

		// Bob's node: contains Bob's legitimate data
		AHashMap<Keyword, ACell> bobPost = SocialPost.createPost("Bob legit", 3000L);
		Index<Blob, ACell> bobFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(3000L), bobPost);
		Index<Keyword, ACell> bobState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, bobFeed);
		SignedData<Index<Keyword, ACell>> bobSigned = bob.signData(bobState);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> honestNode = Maps.of(
			bobDid, bobSigned);

		// Network merge: Bob's node receives Alice's node state
		OwnerLattice<Index<Keyword, ACell>> ownerLattice = Social.SOCIAL_LATTICE;
		LatticeContext ctx = LatticeContext.create(null, bob);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> merged =
			ownerLattice.merge(ctx, honestNode, attackerNode);

		// Alice's legitimate entry survives (correctly signed by Alice under Alice's key)
		SignedData<Index<Keyword, ACell>> aliceResult = merged.get(aliceDid);
		assertNotNull(aliceResult, "Alice's legitimate entry should survive merge");
		assertEquals(alice.getAccountKey(), aliceResult.getAccountKey());
		assertEquals(1, SocialLattice.getFeed(aliceResult.getValue()).count());

		// Bob's legitimate entry survives (correctly signed by Bob under Bob's key)
		SignedData<Index<Keyword, ACell>> bobResult = merged.get(bobDid);
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
			DID.forKey(bob.getAccountKey()), forgedSigned);

		// Receiving node has nothing
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> emptyNode = Maps.empty();

		OwnerLattice<Index<Keyword, ACell>> ownerLattice = Social.SOCIAL_LATTICE;
		LatticeContext ctx = LatticeContext.create(null, bob);

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> merged =
			ownerLattice.merge(ctx, emptyNode, attackerData);

		// Forgery should be rejected — Bob's slot should remain empty
		SignedData<Index<Keyword, ACell>> bobResult = merged.get(DID.forKey(bob.getAccountKey()));
		assertNull(bobResult,
			"Forgery into empty slot should be rejected: signer (Alice) != owner (Bob)");
	}

	@Test
	public void testIndirectDIDRequiresAuthenticatedResolver() {
		AKeyPair signer=AKeyPair.generate();
		AString webDid=convex.core.data.Strings.create("did:web:alice.example");
		Index<Keyword,ACell> state=Index.<Keyword,ACell>none().assoc(
			SocialLattice.KEY_FEED,Index.none());
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> update=Maps.of(
			webDid,signer.signData(state));

		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> denied=
			Social.SOCIAL_LATTICE.merge(LatticeContext.EMPTY,Maps.empty(),update);
		assertNull(denied.get(webDid));

		DIDKeyAuthorizer aliases=DIDKeyAuthorizer.fromAlsoKnownAs(did ->
			did.equals(webDid)?java.util.List.of(DID.forKey(signer.getAccountKey()))
				:java.util.List.of());
		LatticeContext authorised=LatticeContext.create(
			null,null,aliases::verifiesOwner);
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> accepted=
			Social.SOCIAL_LATTICE.merge(authorised,Maps.empty(),update);
		assertNotNull(accepted.get(webDid));
	}

	/**
	 * The owner-aware signing boundary must reject a local write when the context
	 * cannot provide the requested owner's key.
	 */
	@Test
	public void testWrongKeyCannotSignOwnerPath() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();

		Social social = Social.create(alice);

		assertThrows(IllegalStateException.class,
			()->social.user(bob.getAccountKey()).feed().post("Forged!"));

		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> ownerMap = social.cursor().get();
		assertNull(ownerMap.get(DID.forKey(bob.getAccountKey())));
	}
}
