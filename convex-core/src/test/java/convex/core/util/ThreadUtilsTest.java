package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Uninterruptible blocking calls (#776): an interrupt while the caller waits reaches
 * neither the call nor the thread running it, and the caller keeps its interrupt.
 */
public class ThreadUtilsTest {

	@AfterEach
	public void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	public void testUninterruptibleCallRunsElsewhereAndRestoresInterrupt() throws Exception {
		Thread caller=Thread.currentThread();
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		Thread interrupter=new Thread(() -> {
			try {
				if (entered.await(10,TimeUnit.SECONDS)) caller.interrupt();
			} catch (InterruptedException e) {
				// Leave the call to finish when released
			} finally {
				release.countDown();
			}
		});
		interrupter.start();

		Thread worker=ThreadUtils.callUninterruptibly(() -> {
			entered.countDown();
			try {
				if (!release.await(10,TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test signal");
			} catch (InterruptedException e) {
				throw new IOException("Uninterruptible worker was interrupted");
			}
			return Thread.currentThread();
		});
		// Clear the restored interrupt before joining, which would otherwise throw
		boolean restored=Thread.interrupted();
		interrupter.join();

		assertTrue(restored,"Caller interrupt was not restored");
		assertNotSame(caller,worker);
		assertTrue(worker.isDaemon());
		assertFalse(worker.isVirtual(),"Uninterruptible calls must run on platform threads");
		assertEquals("convex-uninterruptible",worker.getName());
	}

	@Test
	public void testUninterruptibleFailurePropagates() {
		IOException failure=new IOException("Injected");
		assertSame(failure,assertThrows(IOException.class,() -> ThreadUtils.runUninterruptibly(() -> { throw failure; })));
		IllegalStateException state=new IllegalStateException("Injected");
		assertSame(state,assertThrows(IllegalStateException.class,() -> ThreadUtils.runUninterruptibly(() -> { throw state; })));
	}
}
