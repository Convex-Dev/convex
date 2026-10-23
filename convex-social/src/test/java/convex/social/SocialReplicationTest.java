package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/** Independent writers and unfamiliar signed sections must not break ordinary replication. */
class SocialReplicationTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(7812);

	private static Social author() {
		return Social.create((owner,value) -> ALICE.signData(value),() -> CVMLong.create(100));
	}

	@Test void sameTimestampPostsFromIndependentReplicasRemainDistinctAndConverge() {
		var a=author();
		var b=author();
		var owner=ALICE.getAccountKey();
		var first=a.user(owner).feed().post("first");
		var second=b.user(owner).feed().post("second");
		assertNotEquals(first,second);
		assertEquals(100,SocialPost.extractTimestamp(first));
		assertEquals(100,SocialPost.extractTimestamp(second));
		var beforeA=a.cursor().get();
		var beforeB=b.cursor().get();
		a.merge(beforeB);
		b.merge(beforeA);
		assertEquals(a.cursor().get(),b.cursor().get());
		assertEquals(2,a.user(owner).feed().count());
		assertEquals("first",SocialPost.getText(a.user(owner).feed().getPost(first)));
		assertEquals("second",SocialPost.getText(a.user(owner).feed().getPost(second)));
		assertTrue(a.cursor().get().get(DID.forKey(owner)).checkSignature());
	}

	@Test void opaqueSectionsDoNotPreventOtherSectionsMerging() {
		var extension=Keyword.intern("future");
		var opaque=Vectors.of("future",1);
		var profile=Maps.of(SocialPost.TIMESTAMP,CVMLong.TWO,Keyword.intern("name"),Strings.create("Alice"));
		List<ACell> followingValues=List.of(opaque,
			Index.of(SocialLattice.KEY_FOLLOWS,opaque),
			Index.of(SocialPost.TIMESTAMP,opaque),
			Index.of(SocialLattice.KEY_FOLLOWS,Maps.of(Strings.create("unknown"),opaque)));
		for (ACell following:followingValues) {
			Index<Keyword,ACell> foreign=Index.of(SocialLattice.KEY_FEED,opaque,
				SocialLattice.KEY_FOLLOWING,following,extension,opaque);
			var social=author();
			var did=DID.forKey(ALICE.getAccountKey());
			var envelope=ALICE.signData(foreign);
			social.merge(Maps.of(did,envelope));
			assertSame(envelope,social.cursor().get().get(did),"Receipt preserves an unfamiliar signed payload");
			Index<Keyword,ACell> update=Index.of(SocialLattice.KEY_PROFILE,profile,
				SocialLattice.KEY_FEED,Index.of(SocialPost.createKey(100,0),SocialPost.createPost("known",100)),
				SocialLattice.KEY_FOLLOWING,Index.of(SocialPost.TIMESTAMP,CVMLong.ONE,SocialLattice.KEY_FOLLOWS,Maps.empty()));
			social.merge(Maps.of(did,ALICE.signData(update)));
			var result=social.cursor().get().get(did).getValue();
			assertEquals(opaque,result.get(SocialLattice.KEY_FEED));
			assertEquals(opaque,result.get(extension));
			assertEquals(profile,result.get(SocialLattice.KEY_PROFILE));
			assertTrue(SocialLattice.getFeed(result).isEmpty());
			assertTrue(SocialHelpers.computeFollowSet(social.cursor().get(),java.util.Set.of(did)).isEmpty());
			// The opposite merge order is safe too; unfamiliar values are not cast to containers.
			assertDoesNotThrow(() -> SocialLattice.INSTANCE.merge(update,foreign));
		}
	}

	@Test void mergeRetainsUnknownFieldsWhileCombiningKnownFeedEntries() {
		var future=Keyword.intern("future");
		var value=Vectors.of(1,2,3);
		Index<Keyword,ACell> own=Index.of(SocialLattice.KEY_FEED,
			Index.of(SocialPost.createKey(100,1),SocialPost.createPost("one",100)));
		Index<Keyword,ACell> other=Index.of(future,value,SocialLattice.KEY_FEED,
			Index.of(SocialPost.createKey(100,2),SocialPost.createPost("two",100)));
		for (var merged:List.of(SocialLattice.INSTANCE.merge(own,other),SocialLattice.INSTANCE.merge(other,own))) {
			assertSame(value,merged.get(future));
			assertEquals(2,SocialLattice.getFeed(merged).count());
		}
	}

	@Test void timelineReadsLegacyAndSaltedKeysAndSkipsUnfamiliarEntries() {
		var legacy=Blob.fromHex("0000000000000064");
		var modern=SocialPost.createKey(101,1);
		Index<Blob,ACell> feed=Index.of(legacy,SocialPost.createPost("legacy",100),
			modern,SocialPost.createPost("modern",101),Blob.fromHex("ff"),Strings.create("opaque"));
		var timeline=SocialHelpers.buildTimeline(Map.of(DID.forKey(ALICE.getAccountKey()),feed),0,10);
		assertEquals(2,timeline.size());
		assertEquals(modern,timeline.get(0).postKey());
		assertEquals(legacy,timeline.get(1).postKey());
		assertEquals(100,SocialPost.extractTimestamp(legacy));
	}

	@Test void followingTimestampPreferencePreservesOpaqueExtensions() {
		var first=Keyword.intern("first-extension");
		var second=Keyword.intern("second-extension");
		var opaque=Vectors.of("opaque");
		Index<Keyword,ACell> a=Index.of(SocialLattice.KEY_FOLLOWING,
			Index.of(SocialPost.TIMESTAMP,CVMLong.ONE,first,opaque));
		Index<Keyword,ACell> b=Index.of(SocialLattice.KEY_FOLLOWING,
			Index.of(SocialPost.TIMESTAMP,CVMLong.TWO,second,opaque));
		for (var merged:List.of(SocialLattice.INSTANCE.merge(a,b),SocialLattice.INSTANCE.merge(b,a))) {
			var following=SocialLattice.getFollowing(merged);
			assertEquals(opaque,following.get(first));
			assertEquals(opaque,following.get(second));
			assertEquals(CVMLong.TWO,following.get(SocialPost.TIMESTAMP));
		}
	}
}
