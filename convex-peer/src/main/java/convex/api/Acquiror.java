package convex.api;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.Utils;

/**
 * One-shot, lifecycle-aware acquisition of a complete CAD3 value tree.
 *
 * <p>An Acquiror owns its virtual worker. Cancelling its future or calling
 * {@link #close()} interrupts that worker and its current request. Callers that must
 * know the worker can no longer touch its destination store can additionally use
 * {@link #awaitTermination(long, TimeUnit)}.
 *
 * <p>The request transport is deliberately abstract. Ordinary clients use the
 * {@link ConvexRemote} constructor, while bidirectional server protocols can supply
 * a {@link DataSource} for their own request correlation.
 */
public final class Acquiror implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(Acquiror.class.getName());

	/** Transport hook for requesting an ordered batch of content hashes. */
	@FunctionalInterface
	public interface DataSource {
		CompletableFuture<Result> request(Hash[] hashes);
	}

	private final Hash hash;
	private final AStore store;
	private final DataSource source;
	private final CompletableFuture<ACell> future = new CompletableFuture<>();
	private final CompletableFuture<Void> termination = new CompletableFuture<>();

	private volatile Thread worker;
	private volatile CompletableFuture<Result> currentRequest;
	private volatile boolean closed;
	private boolean started;

	/**
	 * Creates an Acquiror using a normal remote Convex connection.
	 *
	 * @param hash root content hash to acquire
	 * @param store destination store
	 * @param source remote source
	 */
	public Acquiror(Hash hash, AStore store, ConvexRemote source) {
		this(hash, store, remoteSource(source));
		source.setStore(store);
	}

	/**
	 * Creates an Acquiror with an explicit request transport.
	 *
	 * @param hash root content hash to acquire
	 * @param store destination store
	 * @param source data request transport
	 */
	public Acquiror(Hash hash, AStore store, DataSource source) {
		this.hash = Objects.requireNonNull(hash, "Acquisition hash");
		this.store = Objects.requireNonNull(store, "Acquisition store");
		this.source = Objects.requireNonNull(source, "Acquisition source");
		future.whenComplete((value, error) -> {
			if (future.isCancelled()) cancelOwnedWork();
		});
	}

	private static DataSource remoteSource(ConvexRemote source) {
		Objects.requireNonNull(source, "Remote acquisition source");
		return hashes -> {
			CVMLong id = CVMLong.create(source.getNextID());
			return source.message(Message.createDataRequest(id, hashes));
		};
	}

	public static Acquiror create(Hash hash, AStore store, ConvexRemote source) {
		return new Acquiror(hash, store, source);
	}

	public static Acquiror create(Hash hash, AStore store, DataSource source) {
		return new Acquiror(hash, store, source);
	}

	/**
	 * Starts acquisition once and returns its stable completion future.
	 *
	 * @param <T> expected acquired cell type
	 * @return future for the complete store-backed value
	 */
	@SuppressWarnings("unchecked")
	public synchronized <T extends ACell> CompletableFuture<T> getFuture() {
		if (!started && !closed) {
			started = true;
			log.trace("Trying to acquire remotely: {}", hash);
			Thread thread = Thread.ofVirtual().name("Acquiror").unstarted(this::run);
			worker = thread;
			thread.start();
		}
		if (closed && !future.isDone()) future.cancel(false);
		return (CompletableFuture<T>) (CompletableFuture<?>) future;
	}

	private void run() {
		try {
			ACell value = acquire();
			future.complete(value);
			log.trace("Successfully acquired {}", hash);
		} catch (CancellationException e) {
			future.cancel(false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (closed) {
				future.cancel(false);
			} else {
				future.completeExceptionally(e);
			}
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			future.completeExceptionally((cause != null) ? cause : e);
		} catch (StackOverflowError e) {
			future.completeExceptionally(e);
		} catch (VirtualMachineError e) {
			future.completeExceptionally(e);
			throw e;
		} catch (Exception e) {
			log.warn("Acquisition failed for {}: {}", hash, e.getMessage());
			future.completeExceptionally(e);
		} finally {
			currentRequest = null;
			termination.complete(null);
		}
	}

	private ACell acquire() throws Exception {
		HashSet<Hash> missing = new HashSet<>();

		while (!closed) {
			Ref<ACell> ref = store.refForHash(hash);
			if (closed) throw new CancellationException("Acquisition closed");
			if (ref != null && ref.getStatus() >= Ref.PERSISTED) return ref.getValue();

			missing.clear();
			if (ref == null) {
				missing.add(hash);
			} else {
				ref.findMissing(missing, CPoSConstants.MISSING_LIMIT);
				if (missing.isEmpty()) {
					return Cells.persist(ref.getValue(), store);
				}
			}

			Hash[] requested = missing.toArray(Utils.EMPTY_HASHES);
			if (closed) throw new CancellationException("Acquisition closed");
			CompletableFuture<Result> request = source.request(requested);
			if (request == null) throw new IOException("Acquisition source returned no request future");
			currentRequest = request;
			if (closed) {
				request.cancel(true);
				throw new CancellationException("Acquisition closed");
			}
			Result result;
			try {
				result = request.get();
			} finally {
				currentRequest = null;
			}
			if (closed) throw new CancellationException("Acquisition closed");
			acceptResponse(requested, result);
		}

		throw new CancellationException("Acquisition closed");
	}

	private void acceptResponse(Hash[] requested, Result result)
			throws BadFormatException, IOException, ResultException {
		if (result == null) throw new BadFormatException("Missing acquisition result");
		if (result.isError()) throw new ResultException(result);

		AVector<?> values = RT.ensureVector(result.getValue());
		if (values == null) throw new BadFormatException("Expected vector in acquisition result");

		for (int i = 0; i < values.count(); i++) {
			ACell value = values.get(i);
			if (value == null) {
				Hash missingHash = (i < requested.length) ? requested[i] : hash;
				throw new MissingDataException(store, missingHash);
			}
			// DATA response cells may themselves be partial. Store the top cell and
			// let the normal missing-reference loop request its absent branches.
			Cells.store(value, store);
		}
	}

	/** True once the worker has terminated, or cancellation prevented it starting. */
	public boolean isTerminated() {
		return termination.isDone();
	}

	/** Completion signal for owners that need asynchronous lifecycle composition. */
	public CompletableFuture<Void> getTerminationFuture() {
		return termination;
	}

	/**
	 * Waits for the owned worker to terminate after cancellation or completion.
	 *
	 * @return true if terminated before the timeout
	 */
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		if (termination.isDone()) return true;
		try {
			termination.get(timeout, unit);
			return true;
		} catch (ExecutionException e) {
			return true; // termination is a signal and is never completed exceptionally
		} catch (TimeoutException e) {
			return false;
		}
	}

	/** Cancels the current request and interrupts this Acquiror's owned worker. */
	@Override
	public void close() {
		cancelOwnedWork();
		future.cancel(false);
	}

	private synchronized void cancelOwnedWork() {
		closed = true;
		CompletableFuture<Result> request = currentRequest;
		if (request != null) request.cancel(true);
		interruptWorker();
		if (!started) termination.complete(null);
	}

	private void interruptWorker() {
		Thread thread = worker;
		if (thread != null) thread.interrupt();
	}
}
