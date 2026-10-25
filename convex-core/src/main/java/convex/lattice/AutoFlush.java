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
	private final CompletableFuture<Exception> failure=new CompletableFuture<>();
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
	public CompletionStage<Exception> failure() {
		return failure.minimalCompletionStage();
	}

	private void flush() {
		worker=Thread.currentThread();
		try {
			host.flush();
		} catch (IOException | RuntimeException e) {
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
		try {
			while (!executor.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)) {
				// Wait for an in-progress flush before the caller may close its store.
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while draining automatic flush; close must be retried",e);
		}
		try {
			host.flush();
		} finally {
			closed=true;
			host.releaseAutoFlush(this);
		}
	}
}
