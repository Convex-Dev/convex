package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.message.Message;

/**
 * Tests that a client interrupted while it waits on the calling thread returns an
 * {@code :INTERRUPTED} result and keeps the thread's interrupt status. Each call
 * starts with the status set and waits on a reply that never arrives, so it is
 * interrupted at once.
 */
public class ConvexInterruptTest {

	@AfterEach
	public void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	public void testGetBalance() {
		PendingConvex convex=new PendingConvex();
		Thread.currentThread().interrupt();
		ResultException e=assertThrows(ResultException.class,()->convex.getBalance(Address.create(1)));
		assertEquals(ErrorCodes.INTERRUPTED,e.getResult().getErrorCode());
		assertTrue(Thread.currentThread().isInterrupted());
	}

	@Test
	public void testPingSync() {
		PendingConvex convex=new PendingConvex();
		Thread.currentThread().interrupt();
		ResultException e=assertThrows(ResultException.class,()->convex.pingSync(10000));
		assertEquals(ErrorCodes.INTERRUPTED,e.getResult().getErrorCode());
		assertTrue(Thread.currentThread().isInterrupted());
	}

	@Test
	public void testTransact() {
		PendingConvex convex=new PendingConvex();
		ATransaction tx=Invoke.create(convex.getAddress(),ATransaction.UNKNOWN_SEQUENCE,CVMLong.ONE);
		Thread.currentThread().interrupt();
		CompletableFuture<Result> result;
		// Holding the client's monitor makes it look up the unknown sequence on this thread
		synchronized (convex) {
			result=convex.transact(tx);
		}
		assertTrue(result.isDone());
		assertEquals(ErrorCodes.INTERRUPTED,result.join().getErrorCode());
		assertTrue(Thread.currentThread().isInterrupted());
	}

	/** A client whose queries and requests are never answered. */
	private static final class PendingConvex extends ConvexDirect {
		private PendingConvex() {
			super(Address.create(1234),AKeyPair.createSeeded(1234),null);
		}

		@Override
		public CompletableFuture<Result> query(ACell query, Address address) {
			return new CompletableFuture<>();
		}

		@Override
		public CompletableFuture<Result> message(Message message) {
			return new CompletableFuture<>();
		}
	}
}
