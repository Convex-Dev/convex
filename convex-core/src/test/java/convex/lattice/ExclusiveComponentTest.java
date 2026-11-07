package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.TransactionalRootCursor;

class ExclusiveComponentTest {

	private static final Keyword COUNT=Keyword.intern("count");

	private static final class Counter extends ALatticeComponent<AHashMap<Keyword,ACell>>
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Counter> {
		Counter(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { super(cursor); }
		@Override public Counter withCursor(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return rebind(cursor); }
		@Override protected Counter rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return new Counter(cursor); }
		@Override public Counter fork() { return rebind(storage.fork()); }
		void increment() {
			cursor().updateAndGet(m -> m.assoc(COUNT,CVMLong.create(count(m)+1)));
		}
		long count() { return count(cursor().get()); }
		static long count(AHashMap<Keyword,ACell> map) {
			return ((CVMLong)map.get(COUNT)).longValue();
		}
	}

	private static AHashMap<Keyword,ACell> initial() { return Maps.of(COUNT,CVMLong.ZERO); }

	/** A transactional root counting how often its exclusive lock is taken for a transaction. */
	private static final class CountingRoot extends TransactionalRootCursor<AHashMap<Keyword,ACell>> {
		final AtomicInteger acquisitions=new AtomicInteger();
		CountingRoot() { super(initial()); }
		ReentrantLock real() { return (ReentrantLock)super.exclusiveLock(); }
		@Override protected Lock exclusiveLock() {
			Lock real=real();
			return new Lock() {
				@Override public void lock() { acquisitions.incrementAndGet(); real.lock(); }
				@Override public void unlock() { real.unlock(); }
				@Override public void lockInterruptibly() throws InterruptedException { real.lockInterruptibly(); }
				@Override public boolean tryLock() { return real.tryLock(); }
				@Override public boolean tryLock(long time,TimeUnit unit) throws InterruptedException { return real.tryLock(time,unit); }
				@Override public Condition newCondition() { return real.newCondition(); }
			};
		}
	}

	@Test void eachCallbackRunsOnceUnderTheRootLockAndReturnsItsResult() {
		var root=new CountingRoot();
		Counter live=new Counter(root);
		AtomicInteger calls=new AtomicInteger();
		long count=live.exclusive().transact(draft -> {
			calls.incrementAndGet();
			assertTrue(root.real().isHeldByCurrentThread());
			draft.increment();
			assertEquals(0,live.count(),"Staged changes stay private until commit");
			return draft.count();
		});
		assertEquals(1L,count);
		assertEquals(1,calls.get());
		assertEquals(1,root.acquisitions.get());
		assertEquals(1,live.count());

		var before=root.get();
		assertThrows(IllegalStateException.class,() -> live.exclusive().execute(draft -> {
			draft.increment();
			throw new IllegalStateException("abort");
		}));
		assertSame(before,root.get(),"A failed callback commits nothing");
		assertFalse(root.real().isLocked());
		live.exclusive().execute(Counter::increment);
		assertEquals(2,live.count());
	}

	@Test void readsFollowTheSourceAndEveryOtherChangeIsRefused() {
		var root=new TransactionalRootCursor<AHashMap<Keyword,ACell>>(initial());
		Counter live=new Counter(root);
		var exclusive=live.exclusive();
		assertEquals(0,Counter.count(exclusive.value()));
		live.increment();
		assertEquals(1,Counter.count(exclusive.value()));
		assertTrue(exclusive.exists());
		assertThrows(ReadOnlyCursorException.class,() -> exclusive.cursor().set(initial()));
		assertThrows(ReadOnlyCursorException.class,exclusive::sync);
		assertThrows(UnsupportedOperationException.class,exclusive::fork);
		assertThrows(UnsupportedOperationException.class,exclusive::persist);
		assertThrows(UnsupportedOperationException.class,() -> exclusive.merge(initial()));
		assertThrows(NoSuchPathException.class,() -> exclusive.resolve(Strings.create("count")));
		assertSame(exclusive,exclusive.resolve());
		assertEquals(1,live.count());
	}

	@Test void stagedComponentCommitsToItsForkOverAPlainRoot() {
		var root=new Root<AHashMap<Keyword,ACell>>(initial());
		Counter live=new Counter(root);
		assertThrows(UnsupportedOperationException.class,live::exclusive);
		Counter staged=live.fork();
		long count=staged.exclusive().transact(draft -> {
			draft.increment();
			return draft.count();
		});
		assertEquals(1L,count);
		assertEquals(1,staged.count());
		assertEquals(0,live.count(),"Publishing remains a separate sync");
		staged.sync();
		assertEquals(1,live.count());
	}
}
