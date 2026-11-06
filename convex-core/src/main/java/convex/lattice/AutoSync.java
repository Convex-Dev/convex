package convex.lattice;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * A host's scoped publication worker, for a service that accepts delayed
 * durability. Every interval it runs the service's publish step, if it was given
 * one, then syncs the host: the root is retained and handed to any attached node.
 * The host never chooses what to publish; the service names it in the publish step,
 * such as {@code application::sync}. A failed publish step skips that interval's
 * host sync, as a failed stage of an application sync stops the later ones.
 *
 * <p>The worker never flushes on its interval: pair it with
 * {@link LatticeHost#autoFlush(Duration)} for periodic barriers. Closing it ends
 * with a final publish and save. Closing the host closes this resource first. Its
 * owner closes it, directly or by closing the host, never from two threads at once;
 * closing again does nothing. The store remains caller-owned.</p>
 */
public final class AutoSync implements Closeable {
	private final LatticeHost<?> host;
	private final Runnable publish;
	private final HostWorker worker;
	private boolean closed;

	AutoSync(LatticeHost<?> host,Duration interval,Runnable publish) {
		this.host=host;
		this.publish=publish;
		worker=new HostWorker("Lattice host auto sync",interval,this::sync);
	}

	/**
	 * Completes with the first background failure, if any, including the publish
	 * step's last run when the host closes this resource. Later successful runs do
	 * not erase this signal. Monitoring code can subscribe without polling.
	 */
	public CompletionStage<Throwable> failure() {
		return worker.failure();
	}

	private void sync() {
		if (publish!=null) publish.run();
		host.sync();
	}

	/**
	 * Stops and drains the worker, then publishes and checkpoints once more. The
	 * checkpoint runs even if the publish step fails, saving what is already
	 * published; the publish step's failure is then thrown. Does not close the store.
	 *
	 * @throws IOException if the final checkpoint fails
	 */
	@Override
	public void close() throws IOException {
		if (worker.isWorker()) {
			throw new IllegalStateException("Close automatic sync from its owner, not its failure callback");
		}
		stop(this::finish);
	}

	/**
	 * Stops and drains the worker, then publishes once more, for the host's close,
	 * whose checkpoint saves the result. A failure of the publish step is reported
	 * through {@link #failure()} rather than thrown, so the host's save still runs.
	 */
	void closeForHost() throws IOException {
		stop(() -> {
			Throwable failed=publishLast();
			if (failed!=null) worker.report(failed);
		});
	}

	boolean isWorker() {
		return worker.isWorker();
	}

	private void finish() throws IOException {
		Throwable failed=publishLast();
		try {
			host.checkpoint();
		} catch (IOException | RuntimeException | Error e) {
			if (failed!=null) e.addSuppressed(failed);
			throw e;
		}
		if (failed instanceof RuntimeException e) throw e;
		if (failed instanceof Error e) throw e;
	}

	/**
	 * Runs the publish step once more, returning its failure so the save can still
	 * run. Only failures no service can carry on from are thrown.
	 */
	private Throwable publishLast() {
		if (publish==null) return null;
		try {
			publish.run();
			return null;
		} catch (RuntimeException | Error e) {
			if (HostWorker.isFatal(e)) throw e;
			return e;
		}
	}

	private void stop(HostWorker.Step last) throws IOException {
		if (closed) return;
		try {
			worker.stop(last);
		} finally {
			closed=true;
			host.releaseAutoSync(this);
		}
	}
}
