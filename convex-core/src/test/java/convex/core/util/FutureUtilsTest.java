package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

/** A caller that stops waiting for a future it owns cancels it, so the work behind it can stop. */
public class FutureUtilsTest {

	@Test
	public void getOrCancelReturnsAValueInTime() throws Exception {
		CompletableFuture<String> done=CompletableFuture.completedFuture("value");
		assertEquals("value",FutureUtils.getOrCancel(done,0,TimeUnit.MILLISECONDS));
		assertTrue(!done.isCancelled());
	}

	@Test
	public void getOrCancelCancelsAFutureWithNoValueInTime() {
		CompletableFuture<String> pending=new CompletableFuture<>();
		assertThrows(TimeoutException.class,() -> FutureUtils.getOrCancel(pending,0,TimeUnit.MILLISECONDS));
		assertTrue(pending.isCancelled());
	}

	@Test
	public void getOrCancelCancelsWhenTheWaitIsInterrupted() {
		CompletableFuture<String> pending=new CompletableFuture<>();
		Thread.currentThread().interrupt();
		try {
			assertThrows(InterruptedException.class,() -> FutureUtils.getOrCancel(pending,1,TimeUnit.DAYS));
			assertTrue(pending.isCancelled());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void getOrCancelPassesOnAFailure() {
		IllegalStateException failure=new IllegalStateException("failed");
		CompletableFuture<String> failed=CompletableFuture.failedFuture(failure);
		ExecutionException thrown=assertThrows(ExecutionException.class,() -> FutureUtils.getOrCancel(failed,1,TimeUnit.DAYS));
		assertSame(failure,thrown.getCause());
	}
}
