package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.etch.Etch;
import convex.etch.EtchStore;

/** Host close: the final checkpoint, refusal afterwards, and its automatic-flush, fork and failure cases. */
class LatticeHostCloseTest {
	private static final Keyword KEY=Keyword.intern("close-test");

	@Test void closeSavesTheRootForReopening() throws Exception {
		Index<Keyword,ACell> value=Index.of(KEY,Blobs.createRandom(new Random(),30));
		File file;
		// Resources close in reverse: the host's final checkpoint precedes the store's close
		try (var store=EtchStore.createTemp("host-close"); var host=LatticeHost.create(store)) {
			file=store.getFile();
			host.cursor().set(value);
		}
		try (var store=EtchStore.create(file)) {
			assertEquals(value,LatticeHost.open(store).cursor().get());
		}
	}

	@Test void closingTwiceDoesNothing() throws Exception {
		var flushes=new AtomicInteger();
		var store=new MemoryStore() {
			@Override public void flush() { flushes.incrementAndGet(); }
		};
		var host=LatticeHost.create(CVMLong.ONE,store);
		host.close();
		host.close();
		assertEquals(1,flushes.get());
	}

	@Test void concurrentCloseWaitsForTheCloseInProgress() throws Exception {
		var flushing=new CountDownLatch(1);
		var release=new CountDownLatch(1);
		var flushes=new AtomicInteger();
		var store=new MemoryStore() {
			@Override public void flush() {
				flushes.incrementAndGet();
				flushing.countDown();
				try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			}
		};
		var host=LatticeHost.create(CVMLong.ONE,store);
		host.cursor().set(CVMLong.TWO);
		// e.g. a Shutdown.SERVICE hook and normal shutdown closing together
		var first=closeAsync(host);
		CompletableFuture<Void> second;
		try {
			assertTrue(flushing.await(10,TimeUnit.SECONDS));
			second=closeAsync(host);
			assertFalse(second.isDone(),"A second close cannot return before the first completes");
		} finally { release.countDown(); }
		first.get(10,TimeUnit.SECONDS);
		second.get(10,TimeUnit.SECONDS);
		assertEquals(1,flushes.get());
		assertEquals(CVMLong.TWO,store.getRootData());
	}

	@Test void closedHostRefusesRetentionButStillReads() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		Function<CVMLong,CVMLong> publisher=value -> value;
		host.close();
		assertThrows(IllegalStateException.class,host::sync);
		assertThrows(IllegalStateException.class,() -> host.sync(publisher));
		assertThrows(IllegalStateException.class,host::checkpoint);
		assertThrows(IllegalStateException.class,host::flush);
		assertThrows(IllegalStateException.class,() -> host.autoFlush(Duration.ofDays(1)));
		assertThrows(IllegalStateException.class,() -> host.attachPublisher(publisher));
		host.detachPublisher(publisher);
		assertEquals(CVMLong.ONE,host.cursor().get());
		// The in-memory value can still change, but is no longer retained.
		host.cursor().set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,host.merge(CVMLong.ONE));
		assertEquals(CVMLong.ONE,store.getRootData());
	}

	@Test void closeDrainsAndStopsAutomaticFlushing() throws Exception {
		var flushing=new CountDownLatch(1);
		var release=new CountDownLatch(1);
		var flushes=new AtomicInteger();
		var store=new MemoryStore() {
			@Override public void flush() {
				if (flushes.getAndIncrement()>0) return;
				flushing.countDown();
				try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			}
		};
		var host=LatticeHost.create(CVMLong.ONE,store);
		var durability=host.autoFlush(Duration.ofMillis(1));
		host.cursor().set(CVMLong.TWO);
		CompletableFuture<Void> closing;
		try {
			// The worker holds the host's sync lock inside this flush.
			assertTrue(flushing.await(10,TimeUnit.SECONDS));
			closing=closeAsync(host);
			assertFalse(closing.isDone(),"Close drains the active flush");
		} finally { release.countDown(); }
		closing.get(10,TimeUnit.SECONDS);
		assertEquals(CVMLong.TWO,store.getRootData());
		int flushed=flushes.get();
		assertTrue(flushed>=3,"Drained flush, the resource's final flush and the checkpoint");
		// Already closed: closing it again would otherwise flush the closed host and throw.
		durability.close();
		assertEquals(flushed,flushes.get());
	}

	@Test void storelessCloseStopsAutomaticFlushing() throws Exception {
		var host=LatticeHost.create();
		var durability=host.autoFlush(Duration.ofDays(1));
		host.close();
		assertNull(host.store());
		assertThrows(IllegalStateException.class,host::sync);
		durability.close(); // Already closed by the host, so no flush of the closed host.
	}

	@Test void closingAForkSavesOnlyItsOwnStore() throws Exception {
		var originStore=new MemoryStore();
		var origin=LatticeHost.create(CVMLong.ONE,originStore);
		origin.checkpoint();
		var forkStore=new MemoryStore();
		var fork=origin.fork(forkStore);
		fork.cursor().set(CVMLong.TWO);
		fork.close();
		assertEquals(CVMLong.TWO,forkStore.getRootData());
		assertEquals(CVMLong.ONE,originStore.getRootData());
		assertEquals(CVMLong.ONE,origin.cursor().get());
		assertThrows(IllegalStateException.class,fork::sync);
		assertEquals(CVMLong.ONE,origin.checkpoint(),"The origin stays open");
	}

	@Test void failedFinalFlushLeavesTheHostOpenForRetry() throws Exception {
		var flushes=new AtomicInteger();
		try (var store=new EtchStore(Etch.createTempEtch("host-close-failure")) {
			@Override public void flush() throws IOException {
				if (flushes.getAndIncrement()==0) throw new IOException("disk unavailable");
				super.flush();
			}
		}) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			host.cursor().set(CVMLong.TWO);
			assertEquals("disk unavailable",assertThrows(IOException.class,host::close).getMessage());
			// The root was retained; only the barrier failed.
			assertEquals(CVMLong.TWO,store.getRootData());
			assertEquals(CVMLong.TWO,host.sync(),"Still open");
			host.close();
			assertEquals(2,flushes.get());
			assertThrows(IllegalStateException.class,host::sync);
		}
	}

	@Test void closeWithAPublisherAttachedStillCheckpoints() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var published=new AtomicReference<CVMLong>();
		var publications=new AtomicInteger();
		Function<CVMLong,CVMLong> publisher=value -> {
			publications.incrementAndGet();
			published.set(value);
			return value;
		};
		host.attachPublisher(publisher);
		host.cursor().set(CVMLong.TWO);
		host.close();
		assertEquals(1,publications.get());
		assertEquals(CVMLong.TWO,published.get());
		assertEquals(CVMLong.TWO,store.getRootData());
		host.detachPublisher(publisher); // A node closed after the host still detaches.
	}

	private static CompletableFuture<Void> closeAsync(LatticeHost<?> host) {
		var closed=new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try {
				host.close();
				closed.complete(null);
			} catch (Throwable e) { closed.completeExceptionally(e); }
		});
		return closed;
	}
}
