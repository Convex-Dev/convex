package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;

/** Automatic sync: application and host sync on a schedule, failure reporting, and the final save when it or the host closes. */
class LatticeHostAutoSyncTest {
	/** An interval that never elapses during a test, so only closing runs the worker's steps. */
	private static final Duration NEVER=Duration.ofDays(1);

	/** Records root retention and flushes, in order. */
	private static class RecordingStore extends MemoryStore {
		final List<String> events=new CopyOnWriteArrayList<>();

		@Override public <T extends ACell> Ref<T> setRootData(T data) {
			events.add("retain "+data);
			return super.setRootData(data);
		}

		@Override public void flush() {
			events.add("flush");
			super.flush();
		}
	}

	@Test void syncsApplicationsThenHostOnEachIntervalWithoutFlushing() throws Exception {
		var store=new RecordingStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var runs=new CountDownLatch(2);
		try (var sync=host.autoSync(Duration.ofMillis(1),() -> {
			host.cursor().set(CVMLong.TWO); // The service's application syncing its draft
			runs.countDown();
		})) {
			// The worker starts its second run only after the first run's host sync.
			assertTrue(runs.await(10,TimeUnit.SECONDS));
			assertEquals(CVMLong.TWO,store.getRootData());
			assertFalse(store.events.contains("flush"),"Syncing on an interval never flushes");
		}
		assertEquals("flush",store.events.getLast(),"Closing saves");
	}

	@Test void closingSyncsAndSavesOnceMore() throws Exception {
		var store=new RecordingStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var draft=new AtomicReference<CVMLong>(CVMLong.ONE);
		var sync=host.autoSync(NEVER,() -> host.cursor().set(draft.get()));
		draft.set(CVMLong.TWO);
		sync.close();
		assertEquals(List.of("retain 2","flush"),store.events);
		sync.close(); // Closing again does nothing
		assertEquals(2,store.events.size());
		assertEquals(CVMLong.TWO,host.sync(),"The host stays open");
		host.autoSync(NEVER).close(); // and can start another
	}

	@Test void closingTheHostSyncsApplicationsOnceMoreThenSavesOnce() throws Exception {
		var store=new RecordingStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var draft=new AtomicReference<CVMLong>(CVMLong.ONE);
		var sync=host.autoSync(NEVER,() -> host.cursor().set(draft.get()));
		var flush=host.autoFlush(NEVER);
		draft.set(CVMLong.TWO);
		host.close();
		assertEquals(List.of("retain 2","flush"),store.events,"The last application sync, then one save with its flush");
		assertThrows(IllegalStateException.class,host::sync);
		// Already closed by the host, so neither syncs nor flushes the closed host
		sync.close();
		flush.close();
		assertEquals(2,store.events.size());
	}

	@Test void failedApplicationSyncIsReportedSkipsThatHostSyncAndRetries() throws Exception {
		var store=new RecordingStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var attempts=new AtomicInteger();
		var skipped=new CompletableFuture<Boolean>();
		var synced=new CountDownLatch(1);
		try (var sync=host.autoSync(Duration.ofMillis(1),() -> {
			int attempt=attempts.getAndIncrement();
			if (attempt==0) throw new IllegalStateException("draft invalid");
			if (attempt==1) skipped.complete(store.events.isEmpty());
			if (attempt==2) synced.countDown(); // The second run's host sync has finished
		})) {
			assertEquals("draft invalid",sync.failure().toCompletableFuture().get(10,TimeUnit.SECONDS).getMessage());
			assertTrue(skipped.get(10,TimeUnit.SECONDS),"A failed application sync skips that interval's host sync");
			assertTrue(synced.await(10,TimeUnit.SECONDS));
			assertTrue(store.events.contains("retain 1"),"Later runs sync again");
		}
	}

	@Test void closingTheHostSavesWhenTheLastApplicationSyncFails() throws Exception {
		var store=new RecordingStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var sync=host.autoSync(NEVER,() -> { throw new IllegalStateException("draft invalid"); });
		host.cursor().set(CVMLong.TWO); // A live edit, already in the host root
		host.close();
		assertEquals(List.of("retain 2","flush"),store.events);
		assertEquals("draft invalid",sync.failure().toCompletableFuture().getNow(null).getMessage());
		assertThrows(IllegalStateException.class,host::sync,"Closed");
	}

	@Test void closingDirectlySavesThenThrowsTheApplicationSyncFailure() throws Exception {
		var store=new RecordingStore();
		var host=LatticeHost.create(CVMLong.ONE,store);
		var sync=host.autoSync(NEVER,() -> { throw new IllegalStateException("draft invalid"); });
		host.cursor().set(CVMLong.TWO);
		assertEquals("draft invalid",assertThrows(IllegalStateException.class,sync::close).getMessage());
		assertEquals(List.of("retain 2","flush"),store.events);
		host.autoSync(NEVER).close(); // The host is free to start another
	}

	@Test void oneAutomaticSyncPerHostAndInvalidArgumentsClaimNothing() throws Exception {
		var host=LatticeHost.create();
		assertThrows(IllegalArgumentException.class,() -> host.autoSync(Duration.ZERO));
		assertThrows(NullPointerException.class,() -> host.autoSync(NEVER,null));
		try (var sync=host.autoSync(NEVER)) {
			assertThrows(IllegalStateException.class,() -> host.autoSync(NEVER));
			host.autoFlush(NEVER).close(); // Flushing is a separate worker
		}
		host.autoSync(NEVER).close();
	}

	@Test void closingFromTheFailureCallbackIsRefused() throws Exception {
		var host=LatticeHost.create(CVMLong.ONE,new MemoryStore());
		var registered=new CountDownLatch(1);
		var outcomes=new CompletableFuture<List<String>>();
		var sync=host.autoSync(Duration.ofMillis(1),() -> {
			try { assertTrue(registered.await(10,TimeUnit.SECONDS)); }
			catch (InterruptedException e) { throw new AssertionError(e); }
			throw new IllegalStateException("draft invalid");
		});
		try {
			// Registered before the failure, so the callback runs on the worker
			sync.failure().thenAccept(error -> {
				var refused=new ArrayList<String>();
				try { sync.close(); }
				catch (IllegalStateException e) { refused.add("sync"); }
				catch (IOException e) { refused.add("sync failed: "+e); }
				try { host.close(); }
				catch (IllegalStateException e) { refused.add("host"); }
				catch (IOException e) { refused.add("host failed: "+e); }
				outcomes.complete(refused);
			});
			registered.countDown();
			assertEquals(List.of("sync","host"),outcomes.get(10,TimeUnit.SECONDS));
		} finally {
			host.close(); // Reports the last application sync's failure rather than throwing it
		}
		assertThrows(IllegalStateException.class,host::sync,"Closed by its owner");
	}
}
