package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import convex.core.exceptions.MisbehaviourException;
import convex.core.exceptions.MissingDataException;
import convex.core.message.Message;
import convex.core.store.MemoryStore;
import convex.node.BlockingWriteStore;
import convex.peer.ExpectedLogs;

/** Tests for lifecycle-aware remote data acquisition. */
public class AcquirorTest {

	@Test
	public void decodesWireReplyOnceOnAcquisitionWorker() throws Exception {
		Blob expected = Blob.wrap(new byte[400]);
		var reply = Message.create(Message.createDataResponse(CVMLong.ONE, expected).getMessageData());
		var waiting = new CompletableFuture<Thread>();
		var response = new CompletableFuture<Message>() {
			@Override
			public Message get() throws InterruptedException, ExecutionException {
				waiting.complete(Thread.currentThread());
				return super.get();
			}
		};

		var store = new MemoryStore() {
			Thread decoder;
			int decodes;

			@Override
			public <T extends ACell> T decodeMultiCell(Blob data) throws BadFormatException {
				decoder = Thread.currentThread();
				decodes++;
				return super.decodeMultiCell(data);
			}
		};
		try (store; var acquiror = Acquiror.fromMessages(expected.getHash(), store, hashes -> response)) {
			var acquired = acquiror.getFuture();
			Thread worker = waiting.get(5, TimeUnit.SECONDS);
			// Deliver the undecoded reply from this thread only after the worker awaits it.
			assertTrue(response.complete(reply));
			assertEquals(expected, acquired.get(5, TimeUnit.SECONDS));
			assertSame(worker, store.decoder);
			assertEquals(1, store.decodes);
		}
	}

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

	/**
	 * Closing must not interrupt a worker inside a store write: an interrupt during
	 * Etch channel I/O closes the file channel for every user of the store (#776).
	 * A peer disconnect closes its acquisitions in exactly this state.
	 */
	@Test
	public void testCloseDoesNotInterruptStoreWrite() throws Exception {
		Blob expected = Blobs.createRandom(400);
		try (BlockingWriteStore store = new BlockingWriteStore("Acquiror");
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes ->
					CompletableFuture.completedFuture(Result.create(null, Vectors.of(expected))))) {
			CompletableFuture<ACell> acquired = acquiror.getFuture();
			assertTrue(store.entered.await(5, TimeUnit.SECONDS),
				"the worker should enter the controlled store write");

			acquiror.close();
			store.release();

			assertTrue(acquiror.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(acquired.isCancelled());
			assertFalse(store.wasInterrupted(), "close must never interrupt store I/O");
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

	/** An Error from the source still fails the acquisition. */
	@Test
	public void testErrorFailsAcquisition() throws Exception {
		Blob expected = Blobs.createRandom(400);
		AssertionError defect = new AssertionError("defect in the source");
		// Acquisitions warn of a defect once in the JVM
		try (ExpectedLogs logs = ExpectedLogs.carrying(defect).ifLogged().open();
				MemoryStore store = new MemoryStore();
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes -> { throw defect; })) {
			CompletableFuture<ACell> acquired = acquiror.getFuture();
			assertTrue(acquiror.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(acquired.isCompletedExceptionally());
			assertSame(defect, assertThrows(ExecutionException.class, acquired::get).getCause());
		}
	}

	/** A fetch that fails with an Error fails this acquisition alone, and its worker ends. */
	@Test
	public void testErrorFailsOnlyThisAcquisition() throws Exception {
		Blob expected = Blobs.createRandom(400);
		for (Error simulated : List.of(new OutOfMemoryError("simulated fetch"), new NoClassDefFoundError("simulated fetch"))) {
			// Acquisitions warn of running out of memory, and of a defect, once in the JVM
			try (ExpectedLogs logs = ExpectedLogs.carrying(simulated).ifLogged().open();
					MemoryStore store = new MemoryStore();
					Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes -> {
						throw simulated;
					})) {
				ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
					ExecutionException.class, () -> acquiror.getFuture().get(5, TimeUnit.SECONDS));
				assertSame(simulated, error.getCause());
				assertTrue(acquiror.awaitTermination(5, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void testResponseCardinalityMustMatchRequest() throws Exception {
		assertRejectedResponse(Vectors.empty(), BadFormatException.class);
		assertRejectedResponse(Vectors.of(CVMLong.ZERO, CVMLong.ONE), BadFormatException.class);
	}

	/** A cell other than the one requested is false, not malformed. */
	@Test
	public void testResponseHashMustMatchRequest() throws Exception {
		assertRejectedResponse(Vectors.of(Blobs.createRandom(400)), MisbehaviourException.class);
	}

	private static void assertRejectedResponse(ACell response, Class<? extends Throwable> type) throws Exception {
		Blob expected = Blobs.createRandom(400);
		try (MemoryStore store = new MemoryStore();
				Acquiror acquiror = Acquiror.create(expected.getHash(), store, hashes ->
					CompletableFuture.completedFuture(Result.create(null, response)))) {
			ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class, () -> acquiror.getFuture().get(5, TimeUnit.SECONDS));
			assertInstanceOf(type, error.getCause());
		}
	}

}
