package convex.lattice.kv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;

class KVGCTest {

	private static LatticeKV store(AtomicLong clock) {
		return LatticeKV.create(Strings.create("replica"),() -> CVMLong.create(clock.get()));
	}

	private static LatticeKV view(ACell state) {
		return new LatticeKV(Cursors.root(state),Strings.create("reader"),() -> CVMLong.create(1000));
	}

	private static ACell merge(ACell a,ACell b) { return KVStoreLattice.INSTANCE.merge(a,b); }

	@Test void collectionRejectsStaleReplicasAndPreservesLiveRecords() {
		var time=new AtomicLong(100);
		var kv=store(time);
		kv.set("deleted",Strings.create("old"));
		kv.set("live",Strings.create("kept"));
		kv.set("expired",Strings.create("payload"),50);
		ACell old=kv.cursor().get();
		time.set(200);
		kv.del("deleted");
		assertEquals(2,kv.gc());
		ACell collected=kv.cursor().get();
		assertEquals(CVMLong.create(200),kv.getGCTimestamp());
		assertEquals(CVMLong.create(100),KVEntry.getUTime(kv.entries().get(Strings.create("live"))));
		assertEquals(CVMLong.create(200),KVEntry.getRetainedAt(kv.entries().get(Strings.create("live"))));
		for (ACell result:new ACell[]{merge(collected,old),merge(old,collected),merge(null,collected)}) {
			var replica=view(result);
			assertNull(replica.get("deleted"));
			assertFalse(replica.entries().containsKey(Strings.create("expired")));
			assertEquals(Strings.create("kept"),replica.get("live"));
			assertEquals(kv.getGCTimestamp(),replica.getGCTimestamp());
		}
	}

	@Test void unseenOldWritesAreRejectedButNewWritesRecreateCollectedKeys() {
		var time=new AtomicLong(100);
		var offline=store(time);
		offline.set("unseen",Strings.create("old"));
		var kv=store(time);
		kv.gc(200);
		assertTrue(view(merge(kv.cursor().get(),offline.cursor().get())).entries().isEmpty());
		time.set(200);
		assertThrows(IllegalStateException.class,() -> kv.set("unseen",Strings.create("same time")));
		time.set(201);
		kv.set("unseen",Strings.create("new"));
		assertEquals(Strings.create("new"),view(merge(offline.cursor().get(),kv.cursor().get())).get("unseen"));
	}

	@Test void cutoffIsMonotonicAndCollectionDoesNotRemoveNewerTombstones() {
		var time=new AtomicLong(100);
		var kv=store(time);
		kv.set("key",Strings.create("value"));
		time.set(300);
		kv.del("key");
		assertEquals(0,kv.gc(200));
		assertTrue(KVEntry.isTombstone(kv.entries().get(Strings.create("key"))));
		var before=kv.cursor().get();
		assertEquals(0,kv.gc(100));
		assertSame(before,kv.cursor().get());
		assertEquals(1,kv.gc(300));
		assertTrue(kv.entries().isEmpty());
	}

	@Test void publicationCannotRestorePreCollectionDrafts() {
		var time=new AtomicLong(100);
		var kv=store(time);
		kv.set("live",Strings.create("kept"));
		var draft=kv.fork();
		draft.set("unseen",Strings.create("old"));
		kv.gc(200);
		draft.sync();
		assertNull(kv.get("unseen"));
		assertEquals(Strings.create("kept"),kv.get("live"));
		assertEquals(kv.cursor().get(),draft.cursor().get());
	}

	@Test void cutoffTravelsInsideSignedReplica() {
		var source=KVDatabase.create("db",AKeyPair.createSeeded(9821));
		source.kv().set("key",Strings.create("value"));
		var stale=source.exportReplica();
		source.kv().del("key");
		source.kv().gc();
		var destination=KVDatabase.create("db",AKeyPair.createSeeded(9822));
		assertEquals(1,destination.mergeReplicas(source.exportReplica()));
		assertEquals(1,destination.mergeReplicas(stale));
		assertNull(destination.kv().get("key"));
		assertEquals(source.kv().getGCTimestamp(),destination.kv().getGCTimestamp());
		assertTrue(destination.getSignedState().checkSignature());
	}

	@Test void collectionsAndValueReplicasMergeIndependentlyOfOrder() {
		var time=new AtomicLong(10);
		var a=store(time);
		a.set("live",Strings.create("a"));
		a.gc(100);
		var b=store(time);
		b.gc(50);
		time.set(60);
		var c=store(time);
		c.set("live",Strings.create("stale update"));
		ACell[] states={a.cursor().get(),b.cursor().get(),c.cursor().get(),Index.none()};
		for (ACell x:states) {
			assertEquals(x,merge(x,x));
			for (ACell y:states) {
				assertEquals(merge(x,y),merge(y,x));
				for (ACell z:states) assertEquals(merge(merge(x,y),z),merge(x,merge(y,z)));
			}
		}
	}

	@Test void structuralMergePreservesRetentionAndOlderLiveData() {
		var time=new AtomicLong(10);
		var kv=store(time);
		kv.hset("hash","a",CVMLong.ONE);
		kv.gc(20);
		var other=store(time);
		other.hset("hash","b",CVMLong.ONE);
		other.gc(20);
		var merged=view(merge(kv.cursor().get(),other.cursor().get()));
		assertEquals(CVMLong.ONE,merged.hget("hash","a"));
		assertEquals(CVMLong.ONE,merged.hget("hash","b"));
		assertEquals(CVMLong.create(20),KVEntry.getRetainedAt(merged.entries().get(Strings.create("hash"))));
		assertEquals(merged.cursor().get(),merge(null,merged.cursor().get()));
	}

	@Test void invalidStoreEnvelopesCannotAdvanceTheCutoff() {
		var kv=store(new AtomicLong(10));
		kv.set("key",CVMLong.ONE);
		var state=kv.cursor().get();
		assertSame(state,merge(state,convex.core.data.Vectors.of(Maps.empty(),CVMLong.ONE)));
		assertSame(state,merge(state,convex.core.data.Vectors.of(Index.none(),Strings.create("bad"))));
	}
}
