package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.Lattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.OwnerLattice;

public class SocialLatticeTest {

	// ===== Feed Tests =====

	@Test
	public void testFeedPostCreation() {
		long ts = 1708444800000L;
		Blob key = SocialPost.createKey(ts);
		assertEquals(8, key.count());
		assertEquals(ts, SocialPost.extractTimestamp(key));

		AHashMap<Keyword, ACell> post = SocialPost.createPost("Hello world", ts);
		assertEquals("Hello world", SocialPost.getText(post));
		assertEquals(ts, SocialPost.getTimestamp(post));
		assertFalse(SocialPost.isDeleted(post));
	}

	@Test
	public void testFeedKeyOrdering() {
		// Earlier timestamps should sort before later ones
		Blob key1 = SocialPost.createKey(1000L);
		Blob key2 = SocialPost.createKey(2000L);
		Blob key3 = SocialPost.createKey(3000L);

		assertTrue(key1.compareTo(key2) < 0);
		assertTrue(key2.compareTo(key3) < 0);
		assertTrue(key1.compareTo(key3) < 0);
	}

	@Test
	public void testFeedMergeUnion() {
		// Two feeds with different posts should merge via union
		AHashMap<Keyword, ACell> post1 = SocialPost.createPost("Post 1", 1000L);
		AHashMap<Keyword, ACell> post2 = SocialPost.createPost("Post 2", 2000L);

		Index<Blob, ACell> feedA = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), post1);
		Index<Blob, ACell> feedB = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(2000L), post2);

		Index<Keyword, ACell> stateA = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, feedA);
		Index<Keyword, ACell> stateB = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, feedB);

		Index<Keyword, ACell> merged = SocialLattice.INSTANCE.merge(stateA, stateB);
		Index<Blob, ACell> mergedFeed = SocialLattice.getFeed(merged);

		assertEquals(2, mergedFeed.count());
	}

	@Test
	public void testFeedMergeLWW() {
		// Same key, different timestamps: LWW should pick the later one
		Blob key = SocialPost.createKey(1000L);
		AHashMap<Keyword, ACell> postOld = SocialPost.createPost("Old text", 1000L);
		AHashMap<Keyword, ACell> postNew = SocialPost.createPost("New text", 2000L);

		Index<Blob, ACell> feedA = Index.<Blob, ACell>none().assoc(key, postOld);
		Index<Blob, ACell> feedB = Index.<Blob, ACell>none().assoc(key, postNew);

		Index<Keyword, ACell> stateA = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, feedA);
		Index<Keyword, ACell> stateB = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, feedB);

		Index<Keyword, ACell> merged = SocialLattice.INSTANCE.merge(stateA, stateB);
		Index<Blob, ACell> mergedFeed = SocialLattice.getFeed(merged);

		assertEquals(1, mergedFeed.count());
		AHashMap<Keyword, ACell> result = (AHashMap<Keyword, ACell>) mergedFeed.get(key);
		assertEquals("New text", SocialPost.getText(result));
	}

	@Test
	public void testPostDeletion() {
		long ts = 1000L;
		AHashMap<Keyword, ACell> post = SocialPost.createPost("To be deleted", ts);
		assertFalse(SocialPost.isDeleted(post));

		// Add deletion tombstone
		AHashMap<Keyword, ACell> deleted = post.assoc(SocialPost.DELETED, CVMLong.create(2000L));
		// Update timestamp so LWW picks it
		deleted = deleted.assoc(SocialPost.TIMESTAMP, CVMLong.create(2000L));
		assertTrue(SocialPost.isDeleted(deleted));
	}

	@Test
	public void testReplyPost() {
		AKeyPair kp = AKeyPair.generate();
		Blob parentKey = SocialPost.createKey(1000L);
		AHashMap<Keyword, ACell> reply = SocialPost.createReply(
			"Great post!", 2000L, parentKey, DID.forKey(kp.getAccountKey()));

		assertEquals("Great post!", SocialPost.getText(reply));
		assertEquals(parentKey, reply.get(SocialPost.REPLY_TO));
		assertEquals(DID.forKey(kp.getAccountKey()), reply.get(SocialPost.REPLY_DID));
	}

	// ===== Profile Tests =====

	@Test
	public void testProfileLWW() {
		AHashMap<Keyword, ACell> profileOld = Maps.of(
			SocialPost.TIMESTAMP, CVMLong.create(1000L),
			Keyword.intern("name"), convex.core.data.Strings.create("Alice"));
		AHashMap<Keyword, ACell> profileNew = Maps.of(
			SocialPost.TIMESTAMP, CVMLong.create(2000L),
			Keyword.intern("name"), convex.core.data.Strings.create("Alice Updated"));

		Index<Keyword, ACell> stateA = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_PROFILE, profileOld);
		Index<Keyword, ACell> stateB = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_PROFILE, profileNew);

		Index<Keyword, ACell> merged = SocialLattice.INSTANCE.merge(stateA, stateB);
		AHashMap<Keyword, ACell> profile = SocialLattice.getProfile(merged);
		assertEquals(convex.core.data.Strings.create("Alice Updated"), profile.get(Keyword.intern("name")));
	}

	// ===== Follow Tests =====

	@Test
	public void testFollowToggle() {
		AKeyPair kpTarget = AKeyPair.generate();
		AString targetDid = DID.forKey(kpTarget.getAccountKey());

		// Follow
		AHashMap<Keyword, ACell> followRecord = SocialPost.createFollowRecord(1000L, true);
		assertTrue(SocialPost.isActiveFollow(followRecord));

		// Unfollow (later timestamp)
		AHashMap<Keyword, ACell> unfollowRecord = SocialPost.createFollowRecord(2000L, false);
		assertFalse(SocialPost.isActiveFollow(unfollowRecord));

		// LWW merge: unfollow wins (later timestamp)
		AHashMap<ACell, ACell> followsA = Maps.of(targetDid, followRecord);
		AHashMap<ACell, ACell> followsB = Maps.of(targetDid, unfollowRecord);

		Index<Keyword, ACell> stateA = followingState(1000L,followsA);
		Index<Keyword, ACell> stateB = followingState(2000L,followsB);

		Index<Keyword, ACell> merged = SocialLattice.INSTANCE.merge(stateA, stateB);
		AHashMap<ACell, ACell> follows = SocialLattice.getFollows(merged);

		Set<AString> active = SocialHelpers.getActiveFollows(follows);
		assertTrue(active.isEmpty(), "Unfollowed user should not be in active set");
	}

	@Test
	public void testMultipleFollows() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		AKeyPair kp3 = AKeyPair.generate();

		AHashMap<ACell, ACell> follows = Maps.of(
			DID.forKey(kp1.getAccountKey()), SocialPost.createFollowRecord(1000L, true),
			DID.forKey(kp2.getAccountKey()), SocialPost.createFollowRecord(1000L, true),
			DID.forKey(kp3.getAccountKey()), SocialPost.createFollowRecord(2000L, false));

		Set<AString> active = SocialHelpers.getActiveFollows(follows);
		assertEquals(2, active.size());
		assertTrue(active.contains(DID.forKey(kp1.getAccountKey())));
		assertTrue(active.contains(DID.forKey(kp2.getAccountKey())));
		assertFalse(active.contains(DID.forKey(kp3.getAccountKey())));
	}

	@Test
	public void testSignedOwnerCanAdvanceFromPostToMultipleFollows() {
		AKeyPair alice=AKeyPair.generate();
		AKeyPair receiver=AKeyPair.generate();
		AKeyPair bob=AKeyPair.generate();
		AKeyPair carol=AKeyPair.generate();

		Index<Blob,ACell> feed=Index.<Blob,ACell>none().assoc(
			SocialPost.createKey(1000L),SocialPost.createPost("Hello",1000L));
		Index<Keyword,ACell> postOnly=Index.<Keyword,ACell>none()
			.assoc(SocialLattice.KEY_FEED,feed);
		AHashMap<ACell,ACell> follows=Maps.of(
			DID.forKey(bob.getAccountKey()),SocialPost.createFollowRecord(2000L,true),
			DID.forKey(carol.getAccountKey()),SocialPost.createFollowRecord(2001L,true));
		Index<Keyword,ACell> complete=postOnly.assoc(SocialLattice.KEY_FOLLOWING,
			followingState(2001L,follows).get(SocialLattice.KEY_FOLLOWING));

		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> own=
			Maps.of(DID.forKey(alice.getAccountKey()),alice.signData(postOnly));
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> update=
			Maps.of(DID.forKey(alice.getAccountKey()),alice.signData(complete));
		AHashMap<ACell,SignedData<Index<Keyword,ACell>>> merged=Social.SOCIAL_LATTICE.merge(
			LatticeContext.create(null,receiver),own,update);

		assertEquals(complete,merged.get(DID.forKey(alice.getAccountKey())).getValue());
		assertEquals(Set.of(DID.forKey(bob.getAccountKey()),DID.forKey(carol.getAccountKey())),
			SocialHelpers.getActiveFollows(
				SocialLattice.getFollows(merged.get(DID.forKey(alice.getAccountKey())).getValue())));
	}

	@Test
	public void testFollowingLWPCombinesDisjointReplicaEdits() {
		AString bob=DID.forKey(AKeyPair.generate().getAccountKey());
		AString carol=DID.forKey(AKeyPair.generate().getAccountKey());
		Index<Keyword,ACell> earlier=followingState(1000L,
			Maps.of(bob,SocialPost.createFollowRecord(1000L,true)));
		Index<Keyword,ACell> later=followingState(2000L,
			Maps.of(carol,SocialPost.createFollowRecord(2000L,true)));

		Index<Keyword,ACell> merged=SocialLattice.INSTANCE.merge(earlier,later);
		assertEquals(Set.of(bob,carol),SocialHelpers.getActiveFollows(
			SocialLattice.getFollows(merged)));
		assertEquals(CVMLong.create(2000L),SocialLattice.getFollowing(merged)
			.get(SocialPost.TIMESTAMP));
	}

	@Test
	public void testFollowingLWWResolvesOneTargetOnly() {
		AString bob=DID.forKey(AKeyPair.generate().getAccountKey());
		AString carol=DID.forKey(AKeyPair.generate().getAccountKey());
		Index<Keyword,ACell> earlier=followingState(1000L,Maps.of(
			bob,SocialPost.createFollowRecord(1000L,true),
			carol,SocialPost.createFollowRecord(1000L,true)));
		Index<Keyword,ACell> later=followingState(2000L,Maps.of(
			bob,SocialPost.createFollowRecord(2000L,false)));

		AHashMap<ACell,ACell> follows=SocialLattice.getFollows(
			SocialLattice.INSTANCE.merge(earlier,later));
		assertEquals(Set.of(carol),SocialHelpers.getActiveFollows(follows));
		assertEquals(CVMBool.FALSE,
			((AHashMap<?,?>)follows.get(bob)).get(SocialPost.ACTIVE));
	}

	// ===== Owner Lattice Integration =====

	@Test
	public void testOwnerLatticeSignedFeed() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();

		OwnerLattice<Index<Keyword, ACell>> socialLattice = Social.SOCIAL_LATTICE;

		// Alice creates a post
		AHashMap<Keyword, ACell> alicePost = SocialPost.createPost("Hello from Alice", 1000L);
		Index<Blob, ACell> aliceFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), alicePost);
		Index<Keyword, ACell> aliceState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, aliceFeed);
		SignedData<Index<Keyword, ACell>> aliceSigned = alice.signData(aliceState);

		// Bob creates a post
		AHashMap<Keyword, ACell> bobPost = SocialPost.createPost("Hello from Bob", 2000L);
		Index<Blob, ACell> bobFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(2000L), bobPost);
		Index<Keyword, ACell> bobState = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, bobFeed);
		SignedData<Index<Keyword, ACell>> bobSigned = bob.signData(bobState);

		// Build owner lattice values
		AString aliceDid=DID.forKey(alice.getAccountKey());
		AString bobDid=DID.forKey(bob.getAccountKey());
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> nodeA = Maps.of(
			aliceDid, aliceSigned);
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> nodeB = Maps.of(
			bobDid, bobSigned);

		// Merge
		LatticeContext ctx = LatticeContext.create(null, alice);
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> merged =
			socialLattice.merge(ctx, nodeA, nodeB);

		// Both users present
		assertNotNull(merged.get(aliceDid));
		assertNotNull(merged.get(bobDid));

		// Verify content
		Index<Keyword, ACell> aliceResult = merged.get(aliceDid).getValue();
		Index<Keyword, ACell> bobResult = merged.get(bobDid).getValue();
		assertEquals(1, SocialLattice.getFeed(aliceResult).count());
		assertEquals(1, SocialLattice.getFeed(bobResult).count());
	}

	// ===== Timeline Tests =====

	@Test
	public void testTimelineConstruction() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();

		// Alice: posts at t=1000, t=3000
		Index<Blob, ACell> aliceFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), SocialPost.createPost("Alice first", 1000L))
			.assoc(SocialPost.createKey(3000L), SocialPost.createPost("Alice second", 3000L));

		// Bob: posts at t=2000, t=4000
		Index<Blob, ACell> bobFeed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(2000L), SocialPost.createPost("Bob first", 2000L))
			.assoc(SocialPost.createKey(4000L), SocialPost.createPost("Bob second", 4000L));

		Map<AString, Index<Blob, ACell>> feeds = new HashMap<>();
		feeds.put(DID.forKey(alice.getAccountKey()), aliceFeed);
		feeds.put(DID.forKey(bob.getAccountKey()), bobFeed);

		// Get all posts, newest first
		List<TimelineEntry> timeline = SocialHelpers.buildTimeline(feeds, 0, 10);

		assertEquals(4, timeline.size());
		assertEquals(4000L, timeline.get(0).timestamp());
		assertEquals(3000L, timeline.get(1).timestamp());
		assertEquals(2000L, timeline.get(2).timestamp());
		assertEquals(1000L, timeline.get(3).timestamp());
	}

	@Test
	public void testTimelinePagination() {
		AKeyPair alice = AKeyPair.generate();

		Index<Blob, ACell> feed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), SocialPost.createPost("Post 1", 1000L))
			.assoc(SocialPost.createKey(2000L), SocialPost.createPost("Post 2", 2000L))
			.assoc(SocialPost.createKey(3000L), SocialPost.createPost("Post 3", 3000L))
			.assoc(SocialPost.createKey(4000L), SocialPost.createPost("Post 4", 4000L));

		Map<AString, Index<Blob, ACell>> feeds = Map.of(DID.forKey(alice.getAccountKey()), feed);

		// Page 1: limit 2
		List<TimelineEntry> page1 = SocialHelpers.buildTimeline(feeds, 0, 2);
		assertEquals(2, page1.size());
		assertEquals(4000L, page1.get(0).timestamp());
		assertEquals(3000L, page1.get(1).timestamp());

		// Page 2: before the last entry of page 1
		List<TimelineEntry> page2 = SocialHelpers.buildTimeline(feeds, 3000L, 2);
		assertEquals(2, page2.size());
		assertEquals(2000L, page2.get(0).timestamp());
		assertEquals(1000L, page2.get(1).timestamp());
	}

	@Test
	public void testTimelineFiltersTombstones() {
		AKeyPair alice = AKeyPair.generate();

		AHashMap<Keyword, ACell> deletedPost = SocialPost.createPost("Deleted", 2000L)
			.assoc(SocialPost.DELETED, CVMLong.create(3000L))
			.assoc(SocialPost.TIMESTAMP, CVMLong.create(3000L));

		Index<Blob, ACell> feed = Index.<Blob, ACell>none()
			.assoc(SocialPost.createKey(1000L), SocialPost.createPost("Visible", 1000L))
			.assoc(SocialPost.createKey(2000L), deletedPost);

		Map<AString, Index<Blob, ACell>> feeds = Map.of(DID.forKey(alice.getAccountKey()), feed);
		List<TimelineEntry> timeline = SocialHelpers.buildTimeline(feeds, 0, 10);

		assertEquals(1, timeline.size());
		assertEquals("Visible", SocialPost.getText(timeline.get(0).post()));
	}

	@Test
	public void testTimelineEmpty() {
		List<TimelineEntry> timeline = SocialHelpers.buildTimeline(Map.of(), 0, 10);
		assertTrue(timeline.isEmpty());
	}

	// ===== Null/Empty Handling =====

	@Test
	public void testMergeWithNull() {
		Index<Keyword, ACell> state = Index.<Keyword, ACell>none()
			.assoc(SocialLattice.KEY_FEED, Index.none());

		assertSame(state, SocialLattice.INSTANCE.merge(state, null));
		assertEquals(SocialLattice.INSTANCE.zero(),SocialLattice.INSTANCE.merge(null,state));
	}

	@Test
	public void testEmptyStateFunctions() {
		Index<Keyword, ACell> empty = SocialLattice.INSTANCE.zero();
		assertTrue(SocialLattice.getFeed(empty).isEmpty());
		assertTrue(SocialLattice.getProfile(empty).isEmpty());
		assertTrue(SocialLattice.getFollows(empty).isEmpty());
	}

	@Test
	public void testNullStateFunctions() {
		assertTrue(SocialLattice.getFeed(null).isEmpty());
		assertTrue(SocialLattice.getProfile(null).isEmpty());
		assertTrue(SocialLattice.getFollows(null).isEmpty());
	}

	// ===== KeyedLattice.addLattice Integration =====

	@Test
	public void testAddSocialToRoot() {
		KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
		assertNotNull(root);
		// Should be able to resolve the :social path
		assertNotNull(root.path(Social.KEY_SOCIAL));
	}

	private static Index<Keyword,ACell> followingState(long timestamp,
			AHashMap<ACell,ACell> follows) {
		Index<Keyword,ACell> following=Index.<Keyword,ACell>none()
			.assoc(SocialPost.TIMESTAMP,CVMLong.create(timestamp))
			.assoc(SocialLattice.KEY_FOLLOWS,follows);
		return Index.<Keyword,ACell>none().assoc(SocialLattice.KEY_FOLLOWING,following);
	}
}
