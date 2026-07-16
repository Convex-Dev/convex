package convex.core.util;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-safe dispatcher for a small, rarely changing set of consumers.
 *
 * <p>Registration uses copy-on-write arrays, making dispatch allocation-free.
 * Consumers run synchronously on the calling thread in registration order. A
 * consumer failure is logged and does not prevent delivery to other consumers.</p>
 *
 * @param <V> Type of dispatched value
 */
public final class ConsumerDispatcher<V> implements Consumer<V> {

	private static final System.Logger log=System.getLogger(ConsumerDispatcher.class.getName());

	@SuppressWarnings("rawtypes")
	private static final Consumer[] EMPTY_CONSUMERS=new Consumer[0];

	private volatile Consumer<? super V>[] consumers=emptyConsumers();

	/**
	 * Registers a consumer unless the same instance is already registered.
	 *
	 * @param consumer Consumer to register
	 * @return {@code true} if the consumer was added
	 */
	public synchronized boolean add(Consumer<? super V> consumer) {
		Objects.requireNonNull(consumer,"Consumer cannot be null");
		Consumer<? super V>[] current=consumers;
		for (Consumer<? super V> existing:current) {
			if (existing==consumer) return false;
		}
		Consumer<? super V>[] updated=Arrays.copyOf(current,current.length+1);
		updated[current.length]=consumer;
		consumers=updated;
		return true;
	}

	/**
	 * Removes a registered consumer instance. An in-progress dispatch may still
	 * complete its call to the consumer.
	 *
	 * @param consumer Consumer instance to remove
	 * @return {@code true} if the consumer was removed
	 */
	public synchronized boolean remove(Consumer<? super V> consumer) {
		Consumer<? super V>[] current=consumers;
		for (int i=0;i<current.length;i++) {
			if (current[i]!=consumer) continue;
			if (current.length==1) {
				consumers=emptyConsumers();
			} else {
				Consumer<? super V>[] updated=Arrays.copyOf(current,current.length-1);
				System.arraycopy(current,i+1,updated,i,current.length-i-1);
				consumers=updated;
			}
			return true;
		}
		return false;
	}

	/**
	 * Dispatches a value synchronously to the registered consumers.
	 */
	@Override
	public void accept(V value) {
		for (Consumer<? super V> consumer:consumers) {
			try {
				consumer.accept(value);
			} catch (Exception e) {
				log.log(System.Logger.Level.DEBUG,"Consumer failed during dispatch",e);
			}
		}
	}

	/**
	 * Gets the current number of registered consumers.
	 *
	 * @return Consumer count
	 */
	public int size() {
		return consumers.length;
	}

	public boolean isEmpty() {
		return consumers.length==0;
	}

	@SuppressWarnings("unchecked")
	private static <V> Consumer<? super V>[] emptyConsumers() {
		return (Consumer<? super V>[])EMPTY_CONSUMERS;
	}
}
