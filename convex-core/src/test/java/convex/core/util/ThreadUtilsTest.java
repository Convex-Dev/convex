package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Uninterruptible blocking calls (#776): an interrupt while the caller waits reaches
 * neither the call nor the thread running it, and the caller keeps its interrupt.
 *
 * <p>Each test runs on a thread of its own: an uninterruptible call ignores the
 * interrupt a same-thread timeout would send, and the interrupt this test sends must
 * never reach a thread shared with other tests.</p>
 */
@Timeout(value=60,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
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
		// Interrupts the caller only while it waits: the call cannot return before release
		Thread interrupter=new Thread(() -> {
			try {
				entered.await();
				caller.interrupt();
			} catch (InterruptedException e) {
				// Leave the call to finish when released
			} finally {
				release.countDown();
			}
		});
		interrupter.start();

		Thread worker;
		try {
			worker=ThreadUtils.callUninterruptibly(() -> {
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					throw new IOException("Uninterruptible worker was interrupted");
				}
				return Thread.currentThread();
			});
		} finally {
			// Ends an interrupter still waiting because the call failed; a released one has finished
			interrupter.interrupt();
		}
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
	public void testJoinWaitsThroughAnInterruptAndRestoresIt() throws Exception {
		CountDownLatch release=new CountDownLatch(1);
		Thread target=Thread.ofVirtual().start(() -> {
			try { release.await(); } catch (InterruptedException e) { /* ends */ }
		});
		Thread caller=Thread.currentThread();
		AtomicBoolean returned=new AtomicBoolean();
		// Releases the target only once the interrupted caller is waiting for it again
		Thread releaser=Thread.ofVirtual().start(() -> {
			while (caller.getState()!=Thread.State.WAITING && !returned.get()) Thread.onSpinWait();
			release.countDown();
		});
		caller.interrupt();
		boolean ended;
		try {
			ended=ThreadUtils.joinUninterruptibly(target);
		} finally {
			// Stops the releaser, which then releases the target, even if the join fails
			returned.set(true);
		}
		boolean restored=Thread.interrupted();
		releaser.join();

		assertTrue(ended,"An interrupt must not end the wait");
		assertFalse(target.isAlive());
		assertTrue(restored,"Caller interrupt was not restored");
	}

	@Test
	public void testJoinOnItsOwnThreadReturnsAtOnce() {
		assertFalse(ThreadUtils.joinUninterruptibly(Thread.currentThread()));
		assertFalse(ThreadUtils.joinUninterruptibly(Thread.currentThread(),Duration.ofDays(1)));
	}

	@Test
	public void testTimedJoinReportsAThreadStillRunning() throws Exception {
		CountDownLatch release=new CountDownLatch(1);
		Thread target=Thread.ofVirtual().start(() -> {
			try { release.await(); } catch (InterruptedException e) { /* ends */ }
		});
		try {
			assertFalse(ThreadUtils.joinUninterruptibly(target,Duration.ZERO));
		} finally {
			release.countDown();
		}
		assertTrue(ThreadUtils.joinUninterruptibly(target,Duration.ofDays(1)));
	}

	@Test
	public void testJoinOfAnUnstartedThreadReturnsAtOnce() {
		Thread unstarted=Thread.ofVirtual().unstarted(() -> {});
		assertTrue(ThreadUtils.joinUninterruptibly(unstarted));
		assertTrue(ThreadUtils.joinUninterruptibly(unstarted,Duration.ofDays(1)));
	}

	@Test
	public void testUninterruptibleFailurePropagates() {
		IOException failure=new IOException("Injected");
		assertSame(failure,assertThrows(IOException.class,() -> ThreadUtils.runUninterruptibly(() -> { throw failure; })));
		IllegalStateException state=new IllegalStateException("Injected");
		assertSame(state,assertThrows(IllegalStateException.class,() -> ThreadUtils.runUninterruptibly(() -> { throw state; })));
	}
}
