package convex.lattice.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import convex.core.data.ACell;
import convex.core.data.AHashMap;

/**
 * Bounded, batched producers over a topic, with one worker per partition.
 * Workers edit private topic forks and publish after each batch. Offers apply
 * backpressure until space is available. A worker failure wakes blocked producers
 * and flush callers; subsequent operations report the original cause.
 *
 * <p>Call {@link #start()} before offering records or flushing. {@link #close()}
 * stops admission, drains accepted records and joins all workers. It reports any
 * worker failure and preserves the caller's interrupt status. A closed topic
 * cannot be restarted. Closing this wrapper does not close its primary topic.</p>
 *
 * <pre>{@code
 * try (ConcurrentTopic ct = ConcurrentTopic.create(mq.topic("events"), 4, 4096)) {
 *     ct.start();
 *     ct.offer(key, value);
 *     ct.flush(); // accepted records are now published to the primary topic
 * }
 * }</pre>
 */
public class ConcurrentTopic implements AutoCloseable {

	public static final int DEFAULT_QUEUE_CAPACITY=4096;
	public static final int DEFAULT_BATCH_SIZE=1000;

	private enum State { NEW, RUNNING, CLOSING, CLOSED }
	private final LatticeTopic primary;
	private final int numPartitions;
	private final int queueCapacity;
	private final int batchSize;
	private final AtomicReference<Throwable> failure=new AtomicReference<>();
	private final CompletableFuture<Void> failed=new CompletableFuture<>();
	private volatile State state=State.NEW;
	private volatile Worker[] workers;
	private Thread[] threads;

	private ConcurrentTopic(LatticeTopic primary,int numPartitions,int queueCapacity,int batchSize) {
		this.primary=primary;
		this.numPartitions=numPartitions;
		this.queueCapacity=queueCapacity;
		this.batchSize=batchSize;
	}

	/** Creates an inert worker wrapper and sets the topic's partition-count metadata. */
	public static ConcurrentTopic create(LatticeTopic primary,int numPartitions,int queueCapacity,int batchSize) {
		Objects.requireNonNull(primary,"Primary topic must not be null");
		if (numPartitions<1 || queueCapacity<1 || batchSize<1) {
			throw new IllegalArgumentException("Partition count, queue capacity and batch size must be positive");
		}
		primary.setNumPartitions(numPartitions);
		return new ConcurrentTopic(primary,numPartitions,queueCapacity,batchSize);
	}

	public static ConcurrentTopic create(LatticeTopic primary,int numPartitions,int queueCapacity) {
		return create(primary,numPartitions,queueCapacity,DEFAULT_BATCH_SIZE);
	}

	public static ConcurrentTopic create(LatticeTopic primary,int numPartitions) {
		return create(primary,numPartitions,DEFAULT_QUEUE_CAPACITY,DEFAULT_BATCH_SIZE);
	}

	/** Starts the workers once; repeated calls while running are harmless. */
	public synchronized void start() {
		checkFailure();
		if (state==State.RUNNING) return;
		if (state!=State.NEW) throw new IllegalStateException("Concurrent topic is closed");
		Worker[] prepared=new Worker[numPartitions];
		Thread[] preparedThreads=new Thread[numPartitions];
		for (int i=0;i<numPartitions;i++) {
			prepared[i]=new Worker(primary.fork(),i);
			preparedThreads[i]=new Thread(prepared[i],"lattice-partition-"+i);
			preparedThreads[i].setDaemon(true);
		}
		workers=prepared;
		threads=preparedThreads;
		state=State.RUNNING;
		try {
			for (Thread thread:threads) thread.start();
		} catch (RuntimeException | Error e) {
			fail(e);
			throw e;
		}
	}

	/** Waits for all previously offered records to publish, or reports a worker failure. */
	public void flush() throws InterruptedException {
		ensureRunning();
		CompletableFuture<?>[] markers=new CompletableFuture<?>[numPartitions];
		for (int i=0;i<numPartitions;i++) {
			CompletableFuture<Void> marker=new CompletableFuture<>();
			markers[i]=marker;
			workers[i].enqueue(new InboundRecord(null,null,null,marker));
		}
		try {
			CompletableFuture.anyOf(CompletableFuture.allOf(markers),failed).get();
		} catch (ExecutionException e) {
			throw new IllegalStateException("Concurrent topic worker failed",e.getCause());
		}
	}

	/** Stops admission and waits for accepted work, including when the caller is interrupted. */
	@Override
	public synchronized void close() {
		if (state==State.CLOSED) return;
		if (state==State.NEW) {
			state=State.CLOSED;
			return;
		}
		state=State.CLOSING;
		for (Worker worker:workers) worker.wake();
		boolean interrupted=false;
		try {
			for (Thread thread:threads) {
				while (thread.isAlive()) {
					try { thread.join(); }
					catch (InterruptedException e) { interrupted=true; }
				}
			}
		} finally {
			state=State.CLOSED;
			if (interrupted) Thread.currentThread().interrupt();
		}
		checkFailure();
	}

	/** Offers a non-null keyed value, blocking only while the running worker's inbox is full. */
	public void offer(ACell key,ACell value) throws InterruptedException {
		offer(key,value,null);
	}

	/** Offers a non-null keyed value with optional headers. */
	public void offer(ACell key,ACell value,AHashMap<ACell,ACell> headers) throws InterruptedException {
		Objects.requireNonNull(key,"Partition key must not be null");
		int partition=(int)Math.floorMod(key.getHash().longValue(),(long)numPartitions);
		enqueue(partition,key,value,headers);
	}

	/** Offers a non-null value directly to a partition. */
	public void offer(ACell value,int partition) throws InterruptedException {
		enqueue(partition,null,value,null);
	}

	private void enqueue(int partition,ACell key,ACell value,AHashMap<ACell,ACell> headers)
			throws InterruptedException {
		if (value==null) throw new IllegalArgumentException("Queue value must not be null");
		Objects.checkIndex(partition,numPartitions);
		ensureRunning();
		workers[partition].enqueue(new InboundRecord(key,value,headers,null));
	}

	private void ensureRunning() {
		checkFailure();
		if (state!=State.RUNNING) throw new IllegalStateException("Concurrent topic is not running");
	}

	private void checkFailure() {
		Throwable cause=failure.get();
		if (cause!=null) throw new IllegalStateException("Concurrent topic worker failed",cause);
	}

	private void fail(Throwable cause) {
		if (failure.compareAndSet(null,cause)) {
			failed.completeExceptionally(cause);
			for (Worker worker:workers) worker.wake();
		}
	}

	public LatticeTopic primary() { return primary; }
	public int getNumPartitions() { return numPartitions; }

	public int queueDepth(int partition) {
		Objects.checkIndex(partition,numPartitions);
		Worker[] snapshot=workers;
		return snapshot==null?0:snapshot[partition].depth();
	}

	public long totalRecords() {
		long total=0;
		Worker[] snapshot=workers;
		if (snapshot!=null) for (Worker worker:snapshot) total+=worker.totalRecords.get();
		return total;
	}

	public long totalSyncs() {
		long total=0;
		Worker[] snapshot=workers;
		if (snapshot!=null) for (Worker worker:snapshot) total+=worker.totalSyncs.get();
		return total;
	}

	private record InboundRecord(ACell key,ACell value,AHashMap<ACell,ACell> headers,
			CompletableFuture<Void> flushed) {}

	private final class Worker implements Runnable {
		private final LatticeTopic fork;
		private final int partition;
		private final ArrayDeque<InboundRecord> inbox=new ArrayDeque<>();
		private final AtomicLong totalRecords=new AtomicLong();
		private final AtomicLong totalSyncs=new AtomicLong();

		Worker(LatticeTopic fork,int partition) {
			this.fork=fork;
			this.partition=partition;
		}

		synchronized void enqueue(InboundRecord record) throws InterruptedException {
			ensureRunning();
			while (inbox.size()>=queueCapacity) {
				wait();
				ensureRunning();
			}
			inbox.addLast(record);
			notifyAll();
		}

		synchronized void wake() { notifyAll(); }
		synchronized int depth() { return inbox.size(); }

		private synchronized boolean takeBatch(List<InboundRecord> batch) throws InterruptedException {
			while (inbox.isEmpty() && state==State.RUNNING && failure.get()==null) wait();
			if (failure.get()!=null || inbox.isEmpty()) return false;
			for (int i=0;i<batchSize && !inbox.isEmpty();i++) batch.add(inbox.removeFirst());
			notifyAll();
			return true;
		}

		@Override public void run() {
			try {
				LatticeQueue queue=fork.partition(partition);
				List<InboundRecord> batch=new ArrayList<>(Math.min(batchSize,queueCapacity));
				while (takeBatch(batch)) {
					boolean dirty=false;
					for (InboundRecord record:batch) {
						if (record.flushed()!=null) {
							publish();
							dirty=false;
							record.flushed().complete(null);
						} else {
							queue.offer(record.key(),record.value(),record.headers());
							totalRecords.incrementAndGet();
							dirty=true;
						}
					}
					if (dirty) publish();
					batch.clear();
				}
			} catch (Throwable e) {
				fail(e);
				if (e instanceof InterruptedException) Thread.currentThread().interrupt();
				if (e instanceof Error error) throw error;
			}
		}

		private void publish() {
			fork.sync();
			totalSyncs.incrementAndGet();
		}
	}
}
