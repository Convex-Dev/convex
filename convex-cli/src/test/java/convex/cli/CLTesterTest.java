package convex.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Failures in the harness must reach JUnit, including failures on a worker. */
@Timeout(15)
public class CLTesterTest {
	@Test
	void capturesOutputAndExitStatus() {
		try (CLTester tester=new CLTester()) {
			tester.mainApp.commandLine.setExecutionStrategy(parsed -> {
				tester.mainApp.commandLine.getOut().print("out");
				tester.mainApp.commandLine.getErr().print("err");
				return 7;
			});
			tester.startAsync();
			assertEquals(7,tester.getResult());
			assertEquals("out",tester.getOutput());
			assertEquals("err",tester.getError());
		}
	}

	@Test
	void propagatesWorkerFailure() {
		CLTester tester=new CLTester();
		AssertionError cause=new AssertionError("worker failure");
		tester.mainApp.commandLine.setExecutionStrategy(parsed -> { throw cause; });
		tester.startAsync();
		AssertionError failure=assertThrows(AssertionError.class,tester::ensureComplete);
		assertSame(cause,failure.getCause());
		assertFalse(tester.thread.isAlive());
	}

	@Test
	void timeoutCancelsWorker() throws Exception {
		try (CLTester tester=waitingCommand()) {
			AssertionError failure=assertThrows(AssertionError.class,
					() -> tester.awaitCompletion(Duration.ofMillis(10)));
			assertTrue(failure.getMessage().contains("timed out"));
			assertFalse(tester.thread.isAlive());
		}
	}

	@Test
	void interruptedWaitFailsAndRestoresStatus() throws Exception {
		try (CLTester tester=waitingCommand()) {
			try {
				Thread.currentThread().interrupt();
				AssertionError failure=assertThrows(AssertionError.class,tester::ensureComplete);
				assertInstanceOf(InterruptedException.class,failure.getCause());
				assertTrue(Thread.currentThread().isInterrupted());
				assertFalse(tester.thread.isAlive());
			} finally {
				Thread.interrupted();
			}
		}
	}

	private static CLTester waitingCommand() throws InterruptedException {
		CLTester tester=new CLTester();
		CountDownLatch entered=new CountDownLatch(1);
		tester.mainApp.commandLine.setExecutionStrategy(parsed -> {
			entered.countDown();
			try {
				new CountDownLatch(1).await();
				return 0;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return ExitCodes.INTERRUPT;
			}
		});
		tester.startAsync();
		if (!entered.await(5,TimeUnit.SECONDS)) {
			tester.close();
			fail("CLI worker did not start");
		}
		return tester;
	}
}
