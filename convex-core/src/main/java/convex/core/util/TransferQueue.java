package convex.core.util;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import convex.core.exceptions.TODOException;

/**
 * Non-blocking Queue implementation that overwrites the queued item in a non-blocking manner
 * 
 * @param <E> Type of Queue elements
 */
public class TransferQueue<E> implements BlockingQueue<E> {
	private E item;
	
	@Override
	public synchronized boolean offer(E e) {
		if (e==null) throw new IllegalArgumentException("Cannot offer a null value");
		item=e;
		notify();
		return true;
	}

	@Override
	public synchronized E poll() {
		E result=item;
		item=null;
		return result;
	}
	
	@Override
	public synchronized E take() throws InterruptedException {
		if (item==null) {
			wait();
		};
		E result=item;
		item=null;
		return result;
	}

	@Override
	public synchronized E poll(long timeout, TimeUnit unit) throws InterruptedException {
		if (item==null) {
			wait(unit.toMillis(timeout),0);
		};
		return poll();
	}
	
	@Override
	public void clear() {
		item=null;
	}
	
	@Override
	public boolean add(E e) {
		return offer(e);
	}

	@Override
	public void put(E e) throws InterruptedException {
		boolean sent=offer(e);
		if (!sent) throw new IllegalStateException("Offer shouldn't fail");
	}

	
	@Override
	public E remove() {
		E result=poll();
		if (result==null) throw new NoSuchElementException("Transfer queue empty");
		return result;
	}


	@Override
	public E element() {
		E result=peek();
		if (result==null) throw new NoSuchElementException("Transfer queue empty");
		return result;
	}

	@Override
	public E peek() {
		E result=item;
		return result;
	}

	@Override
	public int size() {
		return (item==null)?0:1;
	}

	@Override
	public boolean isEmpty() {
		return (item==null);
	}

	@Override
	public Iterator<E> iterator() {
		E r=peek();
		if (r==null) return Collections.emptyIterator();
		return Collections.singletonList(r).iterator();
	}

	@Override
	public Object[] toArray() {
		E r=peek();
		if (r==null) return Utils.EMPTY_OBJECTS;
		return new Object[] {r};
	}

	@Override
	public <T> T[] toArray(T[] a) {
		throw new TODOException();
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		throw new TODOException();
	}

	@Override
	public synchronized boolean addAll(Collection<? extends E> c) {
		// Unconditionally offer everything
		for(E e:c) {
			offer(e);
		}
		return true;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		throw new TODOException();
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		throw new TODOException();
	}

	@Override
	public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
		put(e);
		return true;
	}



	@Override
	public int remainingCapacity() {
		// We can always accept one element
		return 1;
	}

	@Override
	public boolean remove(Object o) {
		throw new TODOException();
	}

	@Override
	public boolean contains(Object o) {
		return Utils.equals(o, item);
	}

	@Override
	public int drainTo(Collection<? super E> c) {
		E result=poll();
		if (result==null) return 0;
		c.add(result);
		return 1;
	}

	@Override
	public int drainTo(Collection<? super E> c, int maxElements) {
		if (maxElements<=0) return 0;
		return drainTo(c);
	}

}
