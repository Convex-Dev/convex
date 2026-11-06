package convex.lattice;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * A host's scoped durability worker. Flushes already-retained state; never syncs
 * applications or advances the retained root. The store remains caller-owned.
 * Closing the host closes this resource before its final checkpoint. Its owner
 * closes it, directly or by closing the host, never from two threads at once;
 * closing again does nothing.
 */
public final class AutoFlush implements Closeable {
	private final LatticeHost<?> host;
	private final HostWorker worker;
	private boolean closed;

	AutoFlush(LatticeHost<?> host,Duration interval) {
		this.host=host;
		worker=new HostWorker("Lattice host auto flush",interval,host::flush);
	}

	/**
	 * Completes with the first background failure, if any. Later successful retries
	 * do not erase this signal. Monitoring code can subscribe without polling.
	 */
	public CompletionStage<Throwable> failure() {
		return worker.failure();
	}

	/** Stops and drains the worker, then flushes once more. Does not close the store. */
	@Override
	public void close() throws IOException {
		if (worker.isWorker()) {
			throw new IllegalStateException("Close automatic flushing from its owner, not its failure callback");
		}
		stop(host::flush);
	}

	/** Stops and drains the worker for the host's close, whose checkpoint flushes. */
	void closeForHost() throws IOException {
		stop(null);
	}

	boolean isWorker() {
		return worker.isWorker();
	}

	private void stop(HostWorker.Step last) throws IOException {
		if (closed) return;
		try {
			worker.stop(last);
		} finally {
			closed=true;
			host.releaseAutoFlush(this);
		}
	}
}
