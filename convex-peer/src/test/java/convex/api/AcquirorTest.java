package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
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

	@Test
	public void testNullResponseFailsWithoutRetryPolicy() throws Exception {
		Blob expected = Blobs.createRandom(400);
		AtomicInteger requests = new AtomicInteger();
		try (MemoryStore store = new MemoryStore();
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes -> {
					requests.incrementAndGet();
					return CompletableFuture.completedFuture(
						Result.create(null, Vectors.of((ACell) null)));
				})) {
			ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class, () -> acquiror.getFuture().get(5, TimeUnit.SECONDS));
			MissingDataException missing = assertInstanceOf(MissingDataException.class, error.getCause());
			assertEquals(expected.getHash(), missing.getMissingHash());
			assertEquals(1, requests.get(), "retry policy belongs to the acquisition caller");
		}
	}

	@Test
	public void testResponseCardinalityMustMatchRequest() throws Exception {
		assertMalformedResponse(Vectors.empty());
		assertMalformedResponse(Vectors.of(CVMLong.ZERO, CVMLong.ONE));
	}

	@Test
	public void testResponseHashMustMatchRequest() throws Exception {
		assertMalformedResponse(Vectors.of(Blobs.createRandom(400)));
	}

	private static void assertMalformedResponse(ACell response) throws Exception {
		Blob expected = Blobs.createRandom(400);
		try (MemoryStore store = new MemoryStore();
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes ->
					CompletableFuture.completedFuture(Result.create(null, response)))) {
			ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class, () -> acquiror.getFuture().get(5, TimeUnit.SECONDS));
			assertInstanceOf(BadFormatException.class, error.getCause());
		}
	}

}
