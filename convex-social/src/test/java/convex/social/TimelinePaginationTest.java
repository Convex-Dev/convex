package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

class TimelinePaginationTest {

	private static final AString ALICE=Strings.intern("did:example:alice");
	private static final AString BOB=Strings.intern("did:example:bob");
	private static final Blob LEGACY=Blob.fromHex("00000000000003e8"); // 1000

	// Explicit descending byte order, including unsigned salt ordering and legacy keys.
	private static final List<Blob> KEYS=List.of(
		SocialPost.createKey(1001,0),SocialPost.createKey(1000,-1),
		SocialPost.createKey(1000,256),SocialPost.createKey(1000,1),
		SocialPost.createKey(1000,0),LEGACY,SocialPost.createKey(999,0));

	private static Index<Blob,ACell> feed() {
		Index<Blob,ACell> feed=Index.none();
		for (Blob key:KEYS) feed=feed.assoc(key,SocialPost.createPost("post",SocialPost.extractTimestamp(key)));
		return feed.assoc(SocialPost.createKey(1000,2),SocialPost.createPost("deleted",1000)
				.assoc(SocialPost.DELETED,CVMLong.ONE))
			.assoc(SocialPost.createKey(1002,0),Strings.intern("future post format"))
			.assoc(Blob.fromHex("ff"),SocialPost.createPost("unknown key",1003));
	}

	@Test void everyPageSizeVisitsEachPositionOnceRegardlessOfAuthorIterationOrder() {
		var expected=new ArrayList<TimelinePosition>();
		for (Blob key:KEYS) {
			expected.add(new TimelinePosition(key,BOB));
			expected.add(new TimelinePosition(key,ALICE));
		}
		for (var authors:List.of(List.of(ALICE,BOB),List.of(BOB,ALICE))) {
			var feeds=new LinkedHashMap<AString,Index<Blob,ACell>>();
			for (AString author:authors) feeds.put(author,feed());
			var all=SocialHelpers.buildTimeline(feeds,null,100);
			assertEquals(expected,all.stream().map(TimelineEntry::position).toList());
			for (int i=1;i<all.size();i++) assertTrue(all.get(i-1).compareTo(all.get(i))>0);
			for (int pageSize=1;pageSize<=expected.size();pageSize++) {
				var visited=new ArrayList<TimelineEntry>();
				TimelinePosition before=null;
				// Bounded independently of the implementation, so a broken continuation cannot hang.
				for (int page=0;page<=expected.size();page++) {
					var entries=SocialHelpers.buildTimeline(feeds,before,pageSize);
					if (entries.isEmpty()) break;
					visited.addAll(entries);
					before=entries.getLast().position();
				}
				assertEquals(all,visited,"Pagination must partition the visible timeline at page size "+pageSize);
			}
		}
	}

	@Test void prefixesDeliberatelyExcludeTheirWholeGroup() {
		var feeds=Map.of(ALICE,feed(),BOB,feed());
		var key=SocialPost.createKey(1000,1);
		var exact=SocialHelpers.buildTimeline(feeds,new TimelinePosition(key,BOB),100);
		assertEquals(new TimelinePosition(key,ALICE),exact.getFirst().position());
		var keyOnly=SocialHelpers.buildTimeline(feeds,new TimelinePosition(key),100);
		assertEquals(SocialPost.createKey(1000,0),keyOnly.getFirst().postKey());
		// Truncating a salt deliberately skips all keys with that partial salt.
		var prefix=SocialPost.createKey(1000,256).slice(0,15);
		var partial=SocialHelpers.buildTimeline(feeds,new TimelinePosition(prefix),100);
		assertEquals(key,partial.getFirst().postKey());
		var timestamp=SocialHelpers.buildTimeline(feeds,TimelinePosition.atTimestamp(1000),100);
		assertEquals(2,timestamp.size());
		assertTrue(timestamp.stream().allMatch(entry -> entry.timestamp()==999));
		assertEquals(timestamp,SocialHelpers.buildTimeline(feeds,1000L,100));
		assertTrue(SocialHelpers.buildTimeline(feeds,new TimelinePosition(Blob.EMPTY),100).isEmpty());
	}

	@Test void continuationSurvivesRemovalOfItsEntryAndInsertionAboveIt() {
		var original=feed();
		var first=SocialHelpers.buildTimeline(Map.of(ALICE,original),null,3);
		var before=first.getLast().position();
		var expected=SocialHelpers.buildTimeline(Map.of(ALICE,original),before,100);
		var changed=original.dissoc(before.postKey())
			.assoc(SocialPost.createKey(1005,0),SocialPost.createPost("new",1005));
		assertEquals(expected,SocialHelpers.buildTimeline(Map.of(ALICE,changed),before,100));
	}

	@Test void boundsInGapsAndOutsideFeedsLocateTheCorrectEntries() {
		var feeds=Map.of(ALICE,Index.<Blob,ACell>of(
			SocialPost.createKey(100,0),SocialPost.createPost("first",100),
			SocialPost.createKey(300,0),SocialPost.createPost("third",300)),
			BOB,Index.<Blob,ACell>of(
				SocialPost.createKey(200,0),SocialPost.createPost("second",200),
				SocialPost.createKey(400,0),SocialPost.createPost("fourth",400)));
		var gap=new TimelinePosition(SocialPost.createKey(250,0),ALICE);
		assertEquals(List.of(200L,100L),SocialHelpers.buildTimeline(feeds,gap,10)
			.stream().map(TimelineEntry::timestamp).toList());
		assertEquals(4,SocialHelpers.buildTimeline(feeds,TimelinePosition.atTimestamp(500),10).size());
		assertTrue(SocialHelpers.buildTimeline(feeds,TimelinePosition.atTimestamp(50),10).isEmpty());
	}
}
