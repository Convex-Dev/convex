package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

/**
 * Transactions with writers on real threads. Each behaviour is specified
 * deterministically in {@link TransactionBehaviourTest}; these check that the
 * behaviours hold together when writers really run at the same time.
 */
class TransactionConcurrencyTest {

	private static final Keyword COUNT=Keyword.intern("count");

	private static final class Counter extends ALatticeComponent<AHashMap<Keyword,ACell>>
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Counter> {
		Counter(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { super(cursor); }
		@Override public Counter rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) {
			return new Counter(cursor);
		}
		void increment() {
			cursor().updateAndGet(m -> m.assoc(COUNT,CVMLong.create(count(m)+1)));
		}
		long count() { return count(cursor().get()); }
		private static long count(AHashMap<Keyword,ACell> map) {
			return ((CVMLong)map.get(COUNT)).longValue();
		}
	}

	private static Counter counter() {
		AHashMap<Keyword,ACell> initial=Maps.of(COUNT,CVMLong.ZERO);
		return new Counter(new Root<>(initial));
	}

	/** A draft is thread-safe: work the callback hands to other threads joins the transaction if it finishes first. */
	@Test void callbackCanJoinConcurrentDraftEditsBeforeCommit() {
		Counter live=counter();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			live.execute(draft -> {
				CountDownLatch start=new CountDownLatch(1);
				List<CompletableFuture<Void>> work=new ArrayList<>();
				for (int i=0;i<2;i++) {
					work.add(CompletableFuture.runAsync(() -> {
						await(start);
						for (int j=0;j<32;j++) draft.increment();
					},executor));
				}
				start.countDown();
				CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))
					.orTimeout(10,TimeUnit.SECONDS).join();
				draft.exclusive().execute(Counter::increment);
				assertEquals(65,draft.count());
				assertEquals(0,live.count());
			});
		}
		assertEquals(65,live.count());
	}

	/** Plain writes, optimistic transactions and exclusive ones all survive together. */
	@Test void everyKindOfWriterPreservesEveryChange() throws Exception {
		Counter live=counter();
		CountDownLatch start=new CountDownLatch(1);
		AtomicInteger exclusiveCallbacks=new AtomicInteger();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Void>> work=new ArrayList<>();
			for (int i=0;i<8;i++) {
				int mode=i%3;
				work.add(CompletableFuture.runAsync(() -> {
					await(start);
					for (int j=0;j<100;j++) {
						if (mode==0) live.increment();
						else if (mode==1) live.execute(Counter::increment);
						else live.exclusive().execute(draft -> {
							exclusiveCallbacks.incrementAndGet();
							draft.increment();
						});
					}
				},executor));
			}
			start.countDown();
			CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
		}
		assertEquals(800,live.count());
		assertEquals(200,exclusiveCallbacks.get(),"Each exclusive callback ran once");
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
