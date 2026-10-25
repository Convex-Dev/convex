package convex.lattice;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A host's scoped durability worker. Flushes already-retained state; never syncs
 * applications or advances the retained root. The store remains caller-owned.
 */
public final class AutoFlush implements Closeable {
	private final LatticeHost<?> host;
	private final ScheduledExecutorService executor;
	private final CompletableFuture<Throwable> failure=new CompletableFuture<>();
	private boolean closed;
	private volatile Thread worker;

	AutoFlush(LatticeHost<?> host,Duration interval) {
		long nanos=Objects.requireNonNull(interval).toNanos();
		if (nanos<=0) throw new IllegalArgumentException("Flush interval must be positive");
		this.host=host;
		executor=Executors.newSingleThreadScheduledExecutor(
			Thread.ofVirtual().name("Lattice host auto flush").factory());
		executor.scheduleWithFixedDelay(this::flush,nanos,nanos,TimeUnit.NANOSECONDS);
	}

	/**
	 * Completes with the first background failure, if any. Later successful retries
	 * do not erase this signal. Monitoring code can subscribe without polling.
	 */
	public CompletionStage<Throwable> failure() {
		return failure.minimalCompletionStage();
	}

	private void flush() {
		worker=Thread.currentThread();
		try {
			host.flush();
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			failure.complete(e);
		} catch (Throwable e) {
			// Includes EtchCorruptionError. A periodic task that throws is never run
			// again, so every recoverable failure is reported and flushing continues.
			failure.complete(e);
		}
	}

	/** Stops and drains the worker, then flushes once more. Does not close the store. */
	@Override
	public void close() throws IOException {
		if (Thread.currentThread()==worker) {
			throw new IllegalStateException("Close automatic flushing from its owner, not its failure callback");
		}
		synchronized (this) {
			closeResources();
		}
	}

	private void closeResources() throws IOException {
		if (closed) return;
		executor.shutdown();
		boolean interrupted=false;
		try {
			for (;;) {
				try {
					if (executor.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)) break;
				} catch (InterruptedException e) {
					// A surrounding resource scope may close the store as soon as we
					// return. Drain first, then restore the owner's interruption.
					interrupted=true;
				}
			}
			host.flush();
		} finally {
			closed=true;
			host.releaseAutoFlush(this);
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
