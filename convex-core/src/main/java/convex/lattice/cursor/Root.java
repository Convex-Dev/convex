package convex.lattice.cursor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Atomic value storage. A root retains only its current value and has no callbacks;
 * publication belongs to the component or host that owns it.
 *
 * <p>Writes are optimistic and take no lock. A root can hold one {@link Claim} at a
 * time, which reserves part of its value for one thread: another thread's write that
 * would change that part waits for the release, and writes elsewhere carry on. Reads
 * never wait. Atomic updates go through {@link #compareAndSet}, so a subclass can
 * observe or interleave every update attempt there.</p>
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
		return replace(newValue);
	}

	@Override
	public void set(V newValue) {
		replace(newValue);
	}

	private V replace(V newValue) {
		for (;;) {
			Object current=state.get();
			if (current instanceof Claimed claimed) {
				if (claimed.claim.blocks(newValue)) {
					claimed.claim.awaitRelease();
				} else if (state.compareAndSet(current,new Claimed(newValue,claimed.claim))) {
					return valueOf(current);
				}
			} else if (state.compareAndSet(current,newValue)) {
				return valueOf(current);
			}
		}
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		for (;;) {
			Object current=state.get();
			if (!(current instanceof Claimed claimed)) return state.compareAndSet(expected,newValue);
			if (claimed.value!=expected) return false;
			if (claimed.claim.blocks(newValue)) {
				claimed.claim.awaitRelease();
			} else if (state.compareAndSet(current,new Claimed(newValue,claimed.claim))) {
				return true;
			}
		}
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		for (;;) {
			V current=get();
			if (compareAndSet(current,updateFunction.apply(current))) return current;
		}
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		for (;;) {
			V current=get();
			V next=updateFunction.apply(current);
			if (compareAndSet(current,next)) return next;
		}
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
		 * Whether a write leaving this root value must wait. Compares references:
		 * unchanged cells are shared, and an equal value in a new cell only waits.
		 */
		boolean blocks(Object next) {
			return !isOwnedByCaller() && reader.apply(next)!=seen;
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
