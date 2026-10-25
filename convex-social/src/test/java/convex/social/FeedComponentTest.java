package convex.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

class FeedComponentTest {
	@Test void creationCanBeRefusedWithoutPreventingReadsOrAbsentDeletes() {
		var root=new Root<Index<Blob,ACell>>();
		var author=Strings.intern("did:web:alice.example");
		var feed=new Feed(root,author,() -> CVMLong.ONE) {
			@Override protected Index<Blob,ACell> createContainer() {
				throw new UnsupportedOperationException("Initialise the feed explicitly");
			}
		};
		var missing=SocialPost.createKey(0,0);
		assertSame(author,feed.getAuthor());
		assertNull(feed.getPost(missing));
		assertEquals(0,feed.count());
		feed.delete(missing);
		assertThrows(UnsupportedOperationException.class,() -> feed.post("post"));
		assertThrows(UnsupportedOperationException.class,() -> feed.reply("reply",missing,author));
		assertNull(root.get());
		root.set(Index.none());
		assertNotNull(feed.getPost(feed.post("post")));
	}

	@Test void creationAndTombstonesPreserveInitialRecordsAndUnfamiliarFields() {
		var root=new Root<Index<Blob,ACell>>();
		var initialKey=SocialPost.createKey(0,0);
		var extension=Keyword.intern("future");
		var opaque=Vectors.of("unfamiliar");
		var initial=SocialPost.createPost("initial",0).assoc(extension,opaque);
		var feed=new Feed(root,Strings.intern("did:web:alice.example"),() -> CVMLong.ONE) {
			@Override protected Index<Blob,ACell> createContainer() { return Index.of(initialKey,initial); }
		};
		var key=feed.post("new");
		assertSame(initial,feed.getPost(initialKey));
		var sibling=feed.getPost(key);
		feed.delete(initialKey);
		assertSame(sibling,feed.getPost(key));
		assertSame(opaque,feed.getPost(initialKey).get(extension));
		assertEquals(CVMLong.ONE,feed.getPost(initialKey).get(SocialPost.DELETED));
		assertEquals(CVMLong.ONE,feed.getPost(initialKey).get(SocialPost.TIMESTAMP));
		assertEquals(2,feed.count(),"tombstones remain in the index");
	}

	/** A collision arising during a CAS retry must never overwrite the winning record. */
	@Test void aConcurrentKeyCollisionAbortsThePost() {
		var attempts=new AtomicInteger();
		var clocks=new AtomicInteger();
		var winner=SocialPost.createPost("winner",1);
		var root=new Root<Index<Blob,ACell>>() {
			@Override public Index<Blob,ACell> getAndUpdate(UnaryOperator<Index<Blob,ACell>> update) {
				return super.getAndUpdate(current -> {
					attempts.incrementAndGet();
					var candidate=update.apply(current);
					if (current==null) set(Index.of(candidate.entryAt(0).getKey(),winner));
					return candidate;
				});
			}
		};
		var feed=new Feed(root,Strings.intern("did:web:alice.example"),
			() -> CVMLong.create(clocks.incrementAndGet()));
		assertThrows(IllegalStateException.class,() -> feed.post("loser"));
		assertEquals(2,attempts.get());
		assertEquals(1,clocks.get());
		assertEquals(1,feed.count());
		assertSame(winner,root.get().entryAt(0).getValue());
	}
}
