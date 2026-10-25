package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

/** Reads distinguish absence, empty values and incompatible data without modifying storage. */
class SocialReadTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(7812);
	private static final AString DID_ALICE=DID.forKey(ALICE.getAccountKey());
	private static final Blob POST=SocialPost.createKey(100,1);

	private static Social social(Index<Keyword,ACell> state) {
		var social=Social.create(ALICE);
		social.merge(Maps.of(DID_ALICE,ALICE.signData(state)));
		return social;
	}

	@Test void absentReadsRemainAbsent() {
		var social=Social.create(ALICE);
		var before=social.cursor().get();
		var user=social.user(DID_ALICE);
		assertNull(user.feed().getPost(POST));
		assertEquals(0,user.feed().count());
		assertNull(user.follows().getRecord(DID_ALICE));
		assertNull(user.follows().getActive());
		assertFalse(user.follows().isFollowing(DID_ALICE));
		assertSame(before,social.cursor().get());
		assertNull(SocialLattice.getFollowing(null));
		assertNull(SocialHelpers.getActiveFollows(null));
		assertNull(SocialHelpers.computeFollowSet(null,Set.of(DID_ALICE)));
		assertNull(SocialHelpers.buildTimeline(null,0,10));
	}

	@Test void storedEmptyCollectionsAreReturnedUnchanged() {
		Index<Blob,ACell> feed=Index.none();
		AHashMap<Keyword,ACell> profile=Maps.empty();
		AHashMap<ACell,ACell> follows=Maps.empty();
		Index<Keyword,ACell> following=Index.of(SocialLattice.KEY_FOLLOWS,follows);
		Index<Keyword,ACell> state=Index.of(SocialLattice.KEY_FEED,feed,
			SocialLattice.KEY_PROFILE,profile,SocialLattice.KEY_FOLLOWING,following);
		assertSame(feed,SocialLattice.getFeed(state));
		assertSame(profile,SocialLattice.getProfile(state));
		assertSame(following,SocialLattice.getFollowing(state));
		assertSame(follows,SocialLattice.getFollows(state));
		assertEquals(Set.of(),SocialHelpers.getActiveFollows(follows));
		assertEquals(List.of(),SocialHelpers.buildTimeline(Map.of(),0,10));
	}

	@Test void incompatibleSectionsThrowWithoutBlockingOtherSections() {
		var opaque=Vectors.of("future");
		Index<Keyword,ACell> state=Index.of(SocialLattice.KEY_FEED,opaque,
			SocialLattice.KEY_PROFILE,Maps.empty(),SocialLattice.KEY_FOLLOWING,opaque);
		var social=social(state);
		var before=social.cursor().get();
		var user=social.user(DID_ALICE);
		assertThrows(ClassCastException.class,() -> SocialLattice.getFeed(state));
		assertThrows(ClassCastException.class,() -> SocialLattice.getFollowing(state));
		assertThrows(ClassCastException.class,() -> SocialLattice.getFollows(state));
		assertNotNull(SocialLattice.getProfile(state));
		assertThrows(ClassCastException.class,() -> user.feed().getPost(POST));
		assertThrows(ClassCastException.class,() -> user.feed().count());
		assertThrows(ClassCastException.class,() -> user.feed().post("hello"));
		assertThrows(ClassCastException.class,() -> user.follows().getActive());
		assertThrows(ClassCastException.class,() -> user.follows().follow(DID_ALICE));
		assertSame(before,social.cursor().get());
	}

	@Test void incompatibleFollowMapAndRecordAreNotReplaced() {
		for (ACell follows:List.<ACell>of(CVMLong.ONE,Maps.of(DID_ALICE,CVMLong.ONE))) {
			Index<Keyword,ACell> state=Index.of(SocialLattice.KEY_FOLLOWING,
				Index.of(SocialLattice.KEY_FOLLOWS,follows));
			var social=social(state);
			var before=social.cursor().get();
			var component=social.user(DID_ALICE).follows();
			assertThrows(ClassCastException.class,() -> component.getRecord(DID_ALICE));
			assertThrows(ClassCastException.class,component::getActive);
			if (follows instanceof AHashMap<?,?>) {
				assertThrows(ClassCastException.class,() -> component.follow(DID_ALICE));
			} else {
				assertThrows(IllegalStateException.class,() -> component.follow(DID_ALICE));
			}
			assertSame(before,social.cursor().get());
		}
	}

	@Test void incompatiblePostsCannotDisappearFromReadsOrDeletes() {
		Index<Keyword,ACell> state=Index.of(SocialLattice.KEY_FEED,Index.of(POST,CVMLong.ONE));
		var social=social(state);
		var before=social.cursor().get();
		var feed=social.user(DID_ALICE).feed();
		assertThrows(ClassCastException.class,() -> feed.getPost(POST));
		assertThrows(ClassCastException.class,() -> feed.delete(POST));
		assertSame(before,social.cursor().get());
	}

	@Test void fieldsAreNotCoercedOrDefaultedOnErrors() {
		assertNull(SocialPost.getText(null));
		assertNull(SocialPost.getTimestamp(Maps.empty()));
		assertEquals(0L,SocialPost.getTimestamp(Maps.of(SocialPost.TIMESTAMP,CVMLong.ZERO)));
		assertThrows(ClassCastException.class,() -> SocialPost.getText(Maps.of(SocialPost.TEXT,CVMLong.ONE)));
		assertThrows(ClassCastException.class,() -> SocialPost.getTimestamp(Maps.of(SocialPost.TIMESTAMP,Strings.intern("later"))));
		assertThrows(ClassCastException.class,() -> SocialPost.isDeleted(Maps.of(SocialPost.DELETED,CVMBool.FALSE)));
		assertThrows(ClassCastException.class,() -> SocialPost.isActiveFollow(Maps.of(SocialPost.ACTIVE,CVMLong.ONE)));
		assertThrows(IllegalStateException.class,() -> SocialPost.isActiveFollow(Maps.empty()));
	}

	@Test void derivedQueriesRejectIncompatibleEntries() {
		assertThrows(IllegalArgumentException.class,() -> SocialHelpers.getActiveFollows(
			Maps.of(Strings.intern("invalid DID"),SocialPost.createFollowRecord(100,true))));
		assertThrows(IllegalStateException.class,() -> SocialHelpers.getActiveFollows(Maps.of(DID_ALICE,null)));
		assertThrows(ClassCastException.class,() -> SocialHelpers.buildTimeline(
			Map.of(DID_ALICE,Index.of(POST,CVMLong.ONE)),0,10));
		assertThrows(IllegalArgumentException.class,() -> SocialHelpers.buildTimeline(
			Map.of(DID_ALICE,Index.of(Blob.EMPTY,SocialPost.createPost("bad key",100))),0,10));
		assertThrows(IllegalArgumentException.class,() -> SocialHelpers.buildTimeline(Map.of(),0,-1));
	}
}
