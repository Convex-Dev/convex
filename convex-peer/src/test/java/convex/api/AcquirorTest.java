package convex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.store.MemoryStore;

/** Tests for lifecycle-aware remote data acquisition. */
public class AcquirorTest {

	/** close() owns cancellation of both the request and the virtual worker. */
	@Test
	public void testCloseCancelsRequestAndTerminatesWorker() throws Exception {
		Blob expected = Blobs.createRandom(400);
		CompletableFuture<Result> response = new CompletableFuture<>();
		CountDownLatch requested = new CountDownLatch(1);

		try (MemoryStore store = new MemoryStore();
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes -> {
					requested.countDown();
					return response;
				})) {
			CompletableFuture<ACell> acquired = acquiror.getFuture();
			assertTrue(requested.await(5, TimeUnit.SECONDS));

			acquiror.close();

			assertTrue(acquiror.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(acquired.isCancelled());
			assertTrue(response.isCancelled(),
				"closing the owner must cancel its outstanding transport request");
		}
	}

	/** Cancelling the public future has the same ownership semantics as close(). */
	@Test
	public void testFutureCancellationTerminatesWorker() throws Exception {
		Blob expected = Blobs.createRandom(400);
		CompletableFuture<Result> response = new CompletableFuture<>();
		CountDownLatch requested = new CountDownLatch(1);

		try (MemoryStore store = new MemoryStore();
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes -> {
					requested.countDown();
					return response;
				})) {
			CompletableFuture<ACell> acquired = acquiror.getFuture();
			assertTrue(requested.await(5, TimeUnit.SECONDS));

			assertTrue(acquired.cancel(true));

			assertTrue(acquiror.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(response.isCancelled(),
				"future cancellation must cancel the owned transport request");
		}
	}

}
