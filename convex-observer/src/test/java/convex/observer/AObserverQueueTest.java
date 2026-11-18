package convex.observer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/** Closing an observer queue stops its thread and returns once the thread has ended. */
public class AObserverQueueTest {

	@Test
	public void closeWaitsForTheQueueThread() throws Exception {
		Thread closer=Thread.currentThread();
		CountDownLatch looping=new CountDownLatch(1);
		AtomicBoolean closing=new AtomicBoolean();
		AtomicBoolean closeReturned=new AtomicBoolean();
		AtomicBoolean ended=new AtomicBoolean();
		AObserverQueue<Object> queue=new AObserverQueue<>() {
			@Override
			public void loop() throws InterruptedException {
				looping.countDown();
				try {
					new CountDownLatch(1).await();
				} finally {
					// Ends only once the close waits for it, so a close that does not wait finds it running
					while (!(closing.get() && closer.getState()==Thread.State.WAITING) && !closeReturned.get()) {
						Thread.onSpinWait();
					}
					ended.set(true);
				}
			}
		};
		queue.start();
		try {
			assertTrue(looping.await(5,TimeUnit.SECONDS));
			closing.set(true);
			queue.close();
			boolean waited=ended.get();
			closeReturned.set(true);
			assertTrue(waited,"the queue thread ended before close returned");
		} finally {
			// Lets the queue thread end, then stops it if the test failed before closing; closing again does nothing
			closeReturned.set(true);
			queue.close();
		}
	}

	@Test
	public void closingAQueueNeverStartedDoesNothing() {
		AObserverQueue<Object> queue=new AObserverQueue<>() {
			@Override
			public void loop() {}
		};
		queue.close();
		queue.close();
	}
}
