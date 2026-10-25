package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;

class LatticeHostDurabilityTest {
	@Test void checkpointReturnsSavedSnapshotAndSerialisesPublicationWithFlush() throws Exception {
		var entered=new CountDownLatch(1);
		var release=new CountDownLatch(1);
		try (var store=new MemoryStore() {
			@Override public void flush() {
				entered.countDown();
				try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new AssertionError(e); }
			}
		}; var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			var saved=executor.submit(host::checkpoint);
			try {
				assertTrue(entered.await(10,TimeUnit.SECONDS));
				host.cursor().set(CVMLong.TWO);
				assertEquals(CVMLong.ONE,store.getRootData());
			} finally { release.countDown(); }
			assertEquals(CVMLong.ONE,saved.get(10,TimeUnit.SECONDS));
			assertEquals(CVMLong.TWO,host.cursor().get());
			assertEquals(CVMLong.TWO,host.checkpoint());
		}
	}

	@Test void checkpointFailureDoesNotUndoTheEditAndCanBeRetried() throws Exception {
		var attempts=new AtomicInteger();
		try (var store=new MemoryStore() {
			@Override public void flush() {
				if (attempts.getAndIncrement()==0) throw new IllegalStateException("disk unavailable");
			}
		}) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			assertThrows(IllegalStateException.class,host::checkpoint);
			assertEquals(CVMLong.ONE,host.cursor().get());
			assertEquals(CVMLong.ONE,store.getRootData());
			assertEquals(CVMLong.ONE,host.checkpoint());
			assertEquals(2,attempts.get());
		}
	}

	@Test void automaticFlushReportsFailureRetriesAndNeverPublishesWorkingState() throws Exception {
		var attempts=new AtomicInteger();
		var retried=new CountDownLatch(1);
		try (var store=new MemoryStore() {
			@Override public void flush() {
				if (attempts.getAndIncrement()==0) throw new IllegalStateException("disk unavailable");
				retried.countDown();
			}
		}) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			host.sync();
			host.cursor().set(CVMLong.TWO);
			try (var durability=host.autoFlush(Duration.ofMillis(1))) {
				assertEquals("disk unavailable",durability.failure().toCompletableFuture().get(10,TimeUnit.SECONDS).getMessage());
				assertTrue(retried.await(10,TimeUnit.SECONDS));
				assertThrows(IllegalStateException.class,() -> host.autoFlush(Duration.ofSeconds(1)));
			}
			assertEquals(CVMLong.ONE,store.getRootData());
			assertEquals(CVMLong.TWO,host.cursor().get());
			int before=attempts.get();
			try (var another=host.autoFlush(Duration.ofDays(1))) { }
			assertEquals(before+1,attempts.get(),"Closing performs a final barrier");
			assertEquals(CVMLong.TWO,host.checkpoint(),"Store remains usable");
		}
	}

	@Test void invalidIntervalDoesNotClaimTheHost() throws Exception {
		var host=LatticeHost.create();
		assertThrows(IllegalArgumentException.class,() -> host.autoFlush(Duration.ZERO));
		try (var resource=host.autoFlush(Duration.ofDays(1))) { }
	}
}
