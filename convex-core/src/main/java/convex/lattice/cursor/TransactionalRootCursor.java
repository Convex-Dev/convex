package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Opt-in root supporting exclusive transactions and optimistic-to-serial fallback.
 * All mutation entry points coordinate their commits through one root-owned lock.
 * Ordinary update functions still execute optimistically outside the lock, while
 * reads remain lock-free. Live views inherit this policy, and component working
 * state created against such a root gets an independent root with the same
 * capability. No background workers are created.
 *
 * <p>Choose this variant when constructing the shared host. A locking wrapper
 * around a path cannot exclude writes through other views of an ordinary root.</p>
 *
 * @param <V> Root value type
 */
public class TransactionalRootCursor<V extends ACell> extends Root<V> {

	private final ReentrantLock writeLock=new ReentrantLock();

	public TransactionalRootCursor(V initialValue) {
		super(initialValue);
	}


	@Override
	protected Lock exclusiveLock() {
		return writeLock;
	}

	@Override
	public void set(V value) {
		writeLock.lock();
		try { super.set(value); } finally { writeLock.unlock(); }
	}

	@Override
	public V getAndSet(V value) {
		writeLock.lock();
		try { return super.getAndSet(value); } finally { writeLock.unlock(); }
	}

	@Override
	public boolean compareAndSet(V expected,V value) {
		writeLock.lock();
		try { return super.compareAndSet(expected,value); } finally { writeLock.unlock(); }
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> action) {
		V previous=get();
		V next=action.apply(previous);
		while (!compareAndSet(previous,next)) {
			V current=get();
			if (current!=previous) next=action.apply(current);
			previous=current;
		}
		return previous;
	}

	@Override
	public V updateAndGet(UnaryOperator<V> action) {
		V previous=get();
		V next=action.apply(previous);
		while (!compareAndSet(previous,next)) {
			V current=get();
			if (current!=previous) next=action.apply(current);
			previous=current;
		}
		return next;
	}

	@Override
	public V getAndAccumulate(V value,BinaryOperator<V> action) {
		return getAndUpdate(current -> action.apply(current,value));
	}

	@Override
	public V accumulateAndGet(V value,BinaryOperator<V> action) {
		return updateAndGet(current -> action.apply(current,value));
	}
}
