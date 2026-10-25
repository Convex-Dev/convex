package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

/** Domain navigation shares standard container policy without inventing data on reads. */
class TopicComponentTest {
	private static final Keyword NAME=Keyword.intern("name");
	private static final Keyword FUTURE=Keyword.intern("future");

	@Test void readsAndNoOpTruncationLeaveTheWholeHierarchyAbsent() {
		var root=new Root<AHashMap<ACell,Index<Keyword,ACell>>>();
		var mq=new LatticeMQ(root,() -> CVMLong.ONE);
		var topic=mq.topic("events");
		var queue=topic.partition(0);
		assertNull(topic.get());
		assertNull(topic.getMeta(NAME));
		assertEquals(0,topic.getNumPartitions());
		assertNull(queue.peekFirst());
		assertEquals(0,queue.size());
		assertEquals(0,queue.truncate(0));
		assertNull(root.get());
		assertNull(TopicLattice.getPartitions(null));
		assertNull(TopicLattice.getMeta(null));
		assertNull(TopicLattice.getPartitions(Index.none()));
		assertNull(TopicLattice.getMeta(Index.none()));
		assertThrows(IllegalStateException.class,() -> topic.offer(NAME,CVMLong.ONE));
		assertNull(root.get());
	}

	@Test void creationPolicyAppliesThroughAllDescendants() {
		var root=new Root<AHashMap<ACell,Index<Keyword,ACell>>>();
		var mq=new LatticeMQ(root,() -> CVMLong.ONE) {
			@Override protected AHashMap<ACell,Index<Keyword,ACell>> createContainer() {
				throw new UnsupportedOperationException("Initialise the topic map explicitly");
			}
		};
		var topic=mq.topic("events");
		var queue=topic.partition(0);
		assertNull(topic.getMeta(NAME));
		assertThrows(UnsupportedOperationException.class,() -> topic.setMeta(NAME,CVMLong.ONE));
		assertThrows(UnsupportedOperationException.class,() -> queue.offer(CVMLong.ONE));
		assertNull(root.get(),"a failed ancestor write must not leave partially created descendants");
		root.set(Maps.empty());
		assertEquals(0,queue.offer(CVMLong.ONE));
		topic.setMeta(NAME,CVMLong.TWO);
		assertEquals(CVMLong.ONE,queue.peekFirst());
		assertEquals(CVMLong.TWO,topic.getMeta(NAME));
	}

	@Test void malformedAncestorsAreNotTreatedAsMissing() {
		var root=new Root<ACell>();
		var mq=new LatticeMQ(root.path(),() -> CVMLong.ONE);
		var topic=mq.topic("events");
		var queue=topic.partition(0);
		for (ACell malformed:new ACell[] {CVMLong.ONE,Index.none(),Maps.of("events",CVMLong.ONE)}) {
			root.set(malformed);
			assertThrows(IllegalStateException.class,() -> topic.getMeta(NAME));
			assertThrows(IllegalStateException.class,queue::size);
			assertThrows(IllegalStateException.class,() -> topic.setMeta(NAME,CVMLong.ONE));
			assertThrows(IllegalStateException.class,() -> queue.offer(CVMLong.ONE));
			assertSame(malformed,root.get());
		}
	}

	@Test void operationsInspectOnlyTheirOwnSectionAndPreserveSiblings() {
		var root=Root.create(Index.<Keyword,ACell>of(FUTURE,CVMLong.TWO,TopicLattice.KEY_META,CVMLong.ONE));
		var topic=new LatticeTopic(root,() -> CVMLong.ONE);
		var queue=topic.partition(0);
		queue.offer(CVMLong.ONE); // The opaque metadata is irrelevant to appending.
		assertSame(CVMLong.TWO,root.get().get(FUTURE));
		var before=root.get();
		assertThrows(IllegalStateException.class,() -> topic.getMeta(NAME));
		assertThrows(IllegalStateException.class,() -> topic.setMeta(NAME,CVMLong.ONE));
		assertSame(before,root.get());
		root.set(Index.of(FUTURE,CVMLong.TWO,TopicLattice.KEY_PARTITIONS,CVMLong.ONE));
		topic.setMeta(NAME,CVMLong.ONE); // Likewise, metadata edits leave partitions alone.
		assertSame(CVMLong.TWO,root.get().get(FUTURE));
		assertEquals(CVMLong.ONE,topic.getMeta(NAME));
		before=root.get();
		assertThrows(IllegalStateException.class,queue::size);
		assertThrows(IllegalStateException.class,() -> queue.offer(CVMLong.ONE));
		assertSame(before,root.get());
	}

	@Test void partitionCountsDistinguishAbsenceFromInvalidConfiguration() {
		var topic=LatticeMQ.create().topic("events");
		assertEquals(0,topic.getNumPartitions());
		assertThrows(IllegalArgumentException.class,() -> topic.setNumPartitions(0));
		assertThrows(IllegalArgumentException.class,() -> topic.setNumPartitions(-1));
		assertNull(topic.get());
		topic.setMeta(LatticeTopic.KEY_NUM_PARTITIONS,Strings.intern("four"));
		assertThrows(ClassCastException.class,topic::getNumPartitions);
		assertThrows(ClassCastException.class,() -> topic.offer(NAME,CVMLong.ONE));
		for (long n:new long[] {0,-1}) {
			topic.setMeta(LatticeTopic.KEY_NUM_PARTITIONS,CVMLong.create(n));
			assertThrows(IllegalStateException.class,topic::getNumPartitions);
		}
		topic.setNumPartitions(4);
		assertEquals(4,topic.getNumPartitions());
	}

	@Test void emptySectionsDoNotChangeTopicMergeAndMalformedSectionsThrow() {
		var own=Index.<Keyword,ACell>of(FUTURE,CVMLong.ONE);
		var empty=Index.<Keyword,ACell>of(TopicLattice.KEY_META,Maps.empty(),TopicLattice.KEY_PARTITIONS,Maps.empty());
		assertSame(own,TopicLattice.INSTANCE.merge(own,empty));
		for (Keyword section:new Keyword[] {TopicLattice.KEY_META,TopicLattice.KEY_PARTITIONS}) {
			var malformed=Index.<Keyword,ACell>of(section,CVMLong.ONE);
			assertThrows(ClassCastException.class,() -> TopicLattice.INSTANCE.merge(own,malformed));
		}
	}
}
