package convex.lattice.cursor;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Atomic value storage. A root retains only its current value and has no callbacks;
 * publication belongs to the component or host that owns it.
 *
 * <p>Writes are optimistic and take no lock. A root can hold one {@link Claim} at a
 * time, which reserves part of its value for one thread: another thread's write that
 * would change that part waits for the release, and writes elsewhere carry on. Reads
 * never wait.</p>
 *
 * <p>Every operation decides by value. A write of a value equal to the one held
 * changes nothing and keeps the cells held, so a copy built elsewhere never displaces
 * persisted cells. Two operations replace the cells held with equal ones on purpose:
 * {@link #persist}, and a fork adopting its destination. Which object is held is
 * otherwise this class's own business: atomic updates, compare-and-set and persist
 * all replace it through {@link #install}, so an update that finds the cells switched
 * beneath it runs again on the new ones, and a subclass can observe or interleave
 * every attempt there.</p>
 *
 * @param <V> Value type
 */
public class Root<V extends ACell> extends AForkableCursor<V> {

	/** The current value, or a {@link Claimed} pairing it with the root's one claim. */
	private final AtomicReference<Object> state;

	public Root() {
		this((V) null);
	}

	public Root(V initialValue) {
		this.state = new AtomicReference<>(initialValue);
	}

	/**
	 * Create a new Root cursor with the given initial value
	 * @param <V> Type of cursor values
	 * @param value Initial value for cursor
	 * @return Root cursor instance
	 */
	public static <V extends ACell> Root<V> create(V value) {
		return new Root<V>(value);
	}

	// ===== Standard cursor operations =====

	@Override
	public V get() {
		return valueOf(state.get());
	}

	@Override
	public V getAndSet(V newValue) {
		return replace(newValue,false);
	}

	@Override
	public void set(V newValue) {
		replace(newValue,false);
	}

	/**
	 * Holds exactly this object, even where its value equals the one held. For a holder
	 * switching to cells it knows it should keep, as a fork adopting its destination's.
	 */
	void adopt(V value) {
		replace(value,true);
	}

	private V replace(V newValue,boolean switching) {
		for (;;) {
			Object current=state.get();
			V held=valueOf(current);
			if (!switching && Utils.equals(held,newValue)) return held;
			if (current instanceof Claimed claimed) {
				if (claimed.claim.blocks(newValue)) {
					claimed.claim.awaitRelease();
				} else if (state.compareAndSet(current,new Claimed(newValue,claimed.claim))) {
					return held;
				}
			} else if (state.compareAndSet(current,newValue)) {
				return held;
			}
		}
	}

	/**
	 * Replaces the object held, if it is still exactly {@code held}. This is the one
	 * place a changed write lands, and the only test of object identity: a caller that
	 * fails here looks again, by value.
	 *
	 * @param held Object the caller read
	 * @param next Object to hold in its place
	 * @return false if this root no longer holds that object
	 */
	protected boolean install(V held,V next) {
		for (;;) {
			Object current=state.get();
			if (!(current instanceof Claimed claimed)) return state.compareAndSet(held,next);
			if (claimed.value!=held) return false;
			if (claimed.claim.blocks(next)) {
				claimed.claim.awaitRelease();
			} else if (state.compareAndSet(current,new Claimed(next,claimed.claim))) {
				return true;
			}
		}
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		for (;;) {
			V held=get();
			if (!Utils.equals(held,expected)) return false;
			if (Utils.equals(held,newValue) || install(held,newValue)) return true;
		}
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		for (;;) {
			V held=get();
			V next=updateFunction.apply(held);
			if (Utils.equals(held,next) || install(held,next)) return held;
		}
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		for (;;) {
			V held=get();
			V next=updateFunction.apply(held);
			if (Utils.equals(held,next)) return held;
			if (install(held,next)) return next;
		}
	}

	/**
	 * Replaces the cells held with the equal ones the persister returns. This is not
	 * a write: the value does not change, so it is never an unchanged write to skip,
	 * and it proceeds during a claim. An edit landing meanwhile makes it persist the
	 * edited value instead.
	 */
	@Override
	public V persist(Persister<V> persister) throws IOException {
		for (int attempt=0;attempt<PERSIST_ATTEMPTS;attempt++) {
			V held=get();
			if (held==null) return null;
			V persisted=persister.persist(held);
			if (persisted==held || install(held,persisted)) return persisted;
		}
		return get();
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return getAndUpdate(current -> accumulatorFunction.apply(current,x));
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return updateAndGet(current -> accumulatorFunction.apply(current,x));
	}

	// ===== Claims =====

	@SuppressWarnings("unchecked")
	@Override
	protected Claim claim(Function<? super V,? extends ACell> reader) {
		for (;;) {
			Object current=state.get();
			if (current instanceof Claimed claimed) {
				if (claimed.claim.isOwnedByCaller()) throw new IllegalStateException("This thread already holds this root's claim");
				claimed.claim.awaitRelease();
			} else {
				ACell seen=reader.apply((V)current);
				ActiveClaim claim=new ActiveClaim(state,(Function<Object,ACell>)reader,seen);
				if (state.compareAndSet(current,new Claimed(current,claim))) return claim;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static <V> V valueOf(Object current) {
		return (V)((current instanceof Claimed claimed)?claimed.value:current);
	}

	/** The state of a root while a claim is active. */
	private record Claimed(Object value,ActiveClaim claim) {}

	private static final class ActiveClaim implements Claim {
		private final AtomicReference<Object> state;
		private final Thread owner=Thread.currentThread();
		private final Function<Object,ACell> reader;
		/** What the claimed part held when taken. Only the owner can change it. */
		private final ACell seen;
		/** Held by the owner until release, purely so that other threads can wait on it. */
		private final ReentrantLock released=new ReentrantLock();

		ActiveClaim(AtomicReference<Object> state,Function<Object,ACell> reader,ACell seen) {
			this.state=state;
			this.reader=reader;
			this.seen=seen;
			released.lock();
		}

		boolean isOwnedByCaller() {
			return Thread.currentThread()==owner;
		}

		/**
		 * Whether a write leaving this root value must wait. Compares by value, as a
		 * transaction does: an equal value in new cells, such as the persisted form of
		 * the same data, is no change. It proceeds, and is the value the root then holds.
		 */
		boolean blocks(Object next) {
			return !isOwnedByCaller() && !keepsClaimedPart(next);
		}

		/**
		 * A value the claim's reader refuses is not the value it saw. That is a change to
		 * wait for rather than the writer's failure: the writer knows nothing of this
		 * view, and after the release its write proceeds as if no claim had been taken.
		 */
		private boolean keepsClaimedPart(Object next) {
			try {
				return Utils.equals(reader.apply(next),seen);
			} catch (RuntimeException e) {
				return false;
			}
		}

		void awaitRelease() {
			released.lock();
			released.unlock();
		}

		@Override
		public void close() {
			for (;;) {
				Claimed current=(Claimed)state.get();
				if (state.compareAndSet(current,current.value)) break;
			}
			released.unlock();
		}
	}
}
