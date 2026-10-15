package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.cursor.TransactionalRootCursor;

class TransactionalComponentTest {

	private static final Keyword COUNT=Keyword.intern("count");
	private static final Keyword OTHER=Keyword.intern("other");

	private static final class Counter extends ALatticeComponent<AHashMap<Keyword,ACell>>
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Counter> {
		Counter(ALatticeCursor<AHashMap<Keyword,ACell>> cursor) { super(cursor); }
		@Override public Counter withCursor(ALatticeCursor<AHashMap<Keyword,ACell>> cursor) {
			return new Counter(cursor);
		}
		void increment() {
			cursor.updateAndGet(m -> m.assoc(COUNT,CVMLong.create(count(m)+1)));
		}
		long count() { return count(cursor.get()); }
		static long count(AHashMap<Keyword,ACell> map) {
			return ((CVMLong)map.get(COUNT)).longValue();
		}
	}

	private static AHashMap<Keyword,ACell> initial() { return Maps.of(COUNT,CVMLong.ZERO); }
	private static Counter counter() { return counter(false); }
	private static Counter counter(boolean coordinated) {
		return new Counter(coordinated?new TransactionalRootCursor<>(null,initial())
			:new RootLatticeCursor<>(null,initial()));
	}

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void stagedChangesAreAtomicAndFailuresDiscardThem(boolean coordinated) {
		Counter live=counter(coordinated);
		var before=live.cursor().get();
		AtomicReference<Counter> escaped=new AtomicReference<>();
		assertThrows(IllegalArgumentException.class,() -> live.transact(draft -> {
			escaped.set(draft);
			draft.increment();
			assertSame(before,live.cursor().get());
			throw new IllegalArgumentException("abort");
		}));
		assertSame(before,live.cursor().get());
		assertThrows(IllegalStateException.class,() -> escaped.get().increment());
		live.transact(draft -> {
			draft.increment();
			draft.transact(nested -> nested.increment());
			assertSame(before,live.cursor().get());
		});
		assertEquals(2,live.count());
	}

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void asyncCompletionRunsAfterCommitAndPropagatesFailure(boolean coordinated) throws Exception {
		Counter live=counter(coordinated);
		assertEquals(2L,live.transactAsync(draft -> {
			draft.increment();
			draft.increment();
		}).thenApply(ignored -> live.count()).get(10,TimeUnit.SECONDS));
		var before=live.cursor().get();
		var failed=live.transactAsync(draft -> {
			draft.increment();
			throw new IllegalArgumentException("abort");
		},Runnable::run);
		assertInstanceOf(IllegalArgumentException.class,
			assertThrows(CompletionException.class,failed::join).getCause());
		assertSame(before,live.cursor().get());
	}

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void equalReplacementDoesNotRepeatCallback(boolean coordinated) throws Exception {
		Counter live=counter(coordinated);
		var before=live.cursor().get();
		AHashMap<Keyword,ACell> empty=Maps.empty();
		var equal=empty.assoc(COUNT,CVMLong.ZERO);
		assertEquals(before,equal);
		assertNotSame(before,equal);
		AtomicInteger calls=new AtomicInteger();
		CountDownLatch read=new CountDownLatch(1), replaced=new CountDownLatch(1);
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var writer=executor.submit(() -> {
				await(read);
				live.cursor().set(equal);
				replaced.countDown();
			});
			live.transact(draft -> {
				calls.incrementAndGet();
				read.countDown();
				await(replaced);
				draft.increment();
			});
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(1,calls.get());
		assertEquals(1,live.count());
	}

	@Test void identicalConcurrentResultDoesNotCoalesceOperations() {
		AtomicInteger commits=new AtomicInteger();
		var root=new RootLatticeCursor<AHashMap<Keyword,ACell>>(null,initial()) {
			@Override public boolean compareAndSet(AHashMap<Keyword,ACell> expected,AHashMap<Keyword,ACell> next) {
				if (commits.incrementAndGet()==1) {
					super.set(next); // another increment computes the same value
					return false;
				}
				return super.compareAndSet(expected,next);
			}
		};
		AtomicInteger calls=new AtomicInteger();
		new Counter(root).transact(draft -> { calls.incrementAndGet(); draft.increment(); });
		assertEquals(2,calls.get());
		assertEquals(2,commits.get());
		assertEquals(2,Counter.count(root.get()));
	}

	@Test void conflictingAttemptsFallBackToExclusiveExecution() {
		AtomicInteger exclusiveCalls=new AtomicInteger(), commits=new AtomicInteger(), callbacks=new AtomicInteger();
		var root=new TransactionalRootCursor<AHashMap<Keyword,ACell>>(null,initial()) {
			@Override protected <T> T exclusive(Supplier<T> action) {
				exclusiveCalls.incrementAndGet();
				return super.exclusive(action);
			}
			@Override public boolean compareAndSet(AHashMap<Keyword,ACell> expected,AHashMap<Keyword,ACell> next) {
				if (commits.incrementAndGet()<=3) {
					super.set(expected.assoc(COUNT,CVMLong.create(Counter.count(expected)+10)));
					return false;
				}
				return super.compareAndSet(expected,next);
			}
		};
		Counter live=new Counter(root);
		live.transact(draft -> { callbacks.incrementAndGet(); draft.increment(); });
		assertEquals(4,callbacks.get());
		assertEquals(1,exclusiveCalls.get());
		assertEquals(31,live.count());
	}

	@Test void unrelatedPathChangesReuseTheDraft() throws Exception {
		var root=new RootLatticeCursor<AHashMap<Keyword,ACell>>(null,
			Maps.of(COUNT,initial(),OTHER,CVMLong.ZERO));
		Counter child=new Counter(root.path(COUNT));
		CountDownLatch read=new CountDownLatch(1), changed=new CountDownLatch(1);
		AtomicInteger calls=new AtomicInteger();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var writer=executor.submit(() -> {
				await(read);
				root.path(OTHER).set(CVMLong.ONE);
				changed.countDown();
			});
			child.transact(draft -> {
				calls.incrementAndGet();
				read.countDown();
				await(changed);
				draft.increment();
			});
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(1,calls.get());
		assertEquals(1,child.count());
		assertEquals(CVMLong.ONE,root.get().get(OTHER));
	}

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void lockedTransactionsAndOrdinaryWritersPreserveEveryChange(boolean coordinated) throws Exception {
		Counter live=counter(coordinated);
		CountDownLatch start=new CountDownLatch(1);
		AtomicInteger lockedCallbacks=new AtomicInteger();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Void>> work=new ArrayList<>();
			for (int i=0;i<8;i++) {
				int mode=i%3;
				work.add(CompletableFuture.runAsync(() -> {
					await(start);
					for (int j=0;j<100;j++) {
						if (mode==0) live.increment();
						else if (mode==1 || !coordinated) live.transact(Counter::increment);
						else live.transactLocked(draft -> {
							lockedCallbacks.incrementAndGet();
							draft.increment();
						});
					}
				},executor));
			}
			start.countDown();
			CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
		}
		assertEquals(800,live.count());
		assertEquals(coordinated?200:0,lockedCallbacks.get());
	}

	@Test void transactionOnForkDoesNotTouchLiveState() {
		Counter live=counter(true);
		Counter fork=live.withCursor(live.cursor().fork());
		fork.transactLocked(Counter::increment);
		assertEquals(0,live.count());
		assertEquals(1,fork.count());
		fork.sync();
		assertEquals(1,live.count());
	}

	@Test void ordinaryRootRetriesBeyondTheFallbackThreshold() {
		AtomicInteger commits=new AtomicInteger();
		var root=new RootLatticeCursor<AHashMap<Keyword,ACell>>(null,initial()) {
			@Override public boolean compareAndSet(AHashMap<Keyword,ACell> expected,AHashMap<Keyword,ACell> next) {
				if (commits.incrementAndGet()<=5) return false;
				return super.compareAndSet(expected,next);
			}
		};
		Counter live=new Counter(root);
		AtomicInteger calls=new AtomicInteger();
		live.transact(draft -> { calls.incrementAndGet(); draft.increment(); });
		assertEquals(6,commits.get());
		assertEquals(1,calls.get()); // CAS failed, but the value never changed
		assertEquals(1,live.count());
	}

	@Test void ordinaryRootRejectsLockedTransactionBeforeRunningCallback() {
		Counter live=counter();
		AtomicInteger calls=new AtomicInteger();
		assertFalse(live.cursor().supportsExclusiveTransactions());
		assertThrows(UnsupportedOperationException.class,() -> live.transactLocked(draft -> calls.incrementAndGet()));
		assertEquals(0,calls.get());
		assertEquals(0,live.count());
	}

	@Test void noOpKeepsOriginalReferenceAndMissingPathAbsent() {
		Counter live=counter();
		var before=live.cursor().get();
		live.transact(draft -> draft.cursor().set(initial()));
		assertSame(before,live.cursor().get());
		live.cursor().path(OTHER).transact(draft -> assertNull(draft.get()));
		assertSame(before,live.cursor().get());
	}

	@Test void capabilitiesFollowViewsPathsAndForks() {
		for (boolean coordinated:new boolean[]{false,true}) {
			var root=coordinated?new TransactionalRootCursor<>(null,initial())
				:new RootLatticeCursor<>(null,initial());
			assertEquals(coordinated,root.view().supportsExclusiveTransactions());
			assertEquals(coordinated,root.path(COUNT).supportsExclusiveTransactions());
			assertEquals(coordinated,root.view().fork().supportsExclusiveTransactions());
		}
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
