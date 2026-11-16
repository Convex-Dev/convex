package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;
import convex.net.RequestFailuresTest.RecordingLogger;

/**
 * Tests that a failure reaching the top of a peer loop drops only that iteration,
 * unless the JVM itself has failed. Each failure is simulated: no test exhausts real
 * memory or stack.
 */
public class LoopFailuresTest {

	/** Failures of the peer's own work that a loop carries on after. */
	static final List<Throwable> OWN_WORK=List.of(new StackOverflowError("simulated"),
		new OutOfMemoryError("simulated"),new NoClassDefFoundError("simulated"),
		new AssertionError("simulated"),new IllegalStateException("simulated"));

	@Test
	public void eachKindIsLoggedAboveDebugOnce() {
		RecordingLogger logger=new RecordingLogger();
		// No Server: only a JVM failure uses it
		LoopFailures failures=new LoopFailures(logger,null,"test loop");
		OutOfMemoryError oom=new OutOfMemoryError("simulated");
		IllegalStateException defect=new IllegalStateException("simulated");
		List<Throwable> sequence=List.of(oom,new OutOfMemoryError("simulated"),
			defect,new IllegalStateException("simulated"),new AssertionError("simulated"));
		for (Throwable e: sequence) assertTrue(failures.handle(e),e.toString());
		assertEquals(List.of(Level.WARN,Level.DEBUG,Level.ERROR,Level.DEBUG,Level.ERROR),logger.levels);
		assertSame(oom,logger.causes.get(0));
		assertSame(defect,logger.causes.get(2));
	}

	/**
	 * A component whose loop throws chosen failures in turn and then waits. It counts
	 * each time the loop comes round, so a test can wait for it to come round again.
	 */
	static final class FailingComponent extends AThreadedComponent {
		final LinkedBlockingQueue<Throwable> failures;
		final CountDownLatch rounds;

		FailingComponent(Server server, List<Throwable> failures) {
			super(server);
			this.failures=new LinkedBlockingQueue<>(failures);
			// One round for each failure, and one more after the last
			this.rounds=new CountDownLatch(failures.size()+1);
		}

		@Override
		protected void loop() throws InterruptedException {
			rounds.countDown();
			throw Utils.<RuntimeException>sneakyThrow(failures.take());
		}

		@Override
		protected String getThreadName() {
			return "Failing test component";
		}
	}

	/** A component's thread carries on after any failure of its own work. */
	@Test
	public void aComponentCarriesOnAfterAnyFailureOfItsOwnWork() throws Exception {
		ExpectedLogs logs=ExpectedLogs.carrying(OWN_WORK).open();
		try (logs) {
			Server peer=InvalidPeerDataTest.launchPeer(InvalidPeerDataTest.LOCAL);
			FailingComponent component=new FailingComponent(peer,OWN_WORK);
			try {
				component.start();
				assertTrue(component.rounds.await(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS),"the loop comes round after every failure");
				assertTrue(peer.isRunning());
			} finally {
				component.close();
				peer.close();
			}
		}
		logs.assertLogged(Level.ERROR,OWN_WORK.get(0));
		logs.assertLogged(Level.WARN,OWN_WORK.get(1));
	}

	/** A failure of the JVM itself in a component's loop shuts the peer down. */
	@Test
	public void aJvmFailureInAComponentShutsThePeerDown() throws Exception {
		InternalError failure=new InternalError("simulated");
		ExpectedLogs logs=ExpectedLogs.carrying(failure).open();
		try (logs) {
			Server peer=InvalidPeerDataTest.launchPeer(InvalidPeerDataTest.LOCAL);
			FailingComponent component=new FailingComponent(peer,List.of(failure));
			try {
				component.start();
				// Preemptive: without the shutdown, the wait would never return
				assertTimeoutPreemptively(Duration.ofSeconds(InvalidPeerDataTest.WAIT),() -> peer.waitForShutdown());
				assertFalse(peer.isRunning());
			} finally {
				component.close();
				peer.close();
			}
		}
		logs.assertLogged(Level.ERROR,failure);
	}

	/** The connection manager's loop carries on after a failure of its own upkeep. */
	@SuppressWarnings("serial")
	@Test
	public void theConnectionManagerCarriesOnAfterAFailure() throws Exception {
		CountDownLatch reads=new CountDownLatch(2);
		AtomicBoolean failed=new AtomicBoolean();
		NoClassDefFoundError failure=new NoClassDefFoundError("simulated");
		// Read on every pass of the manager's loop: fails the first time
		Number outgoing=new Number() {
			@Override public long longValue() {
				reads.countDown();
				if (failed.compareAndSet(false,true)) throw failure;
				return 2;
			}
			@Override public int intValue() { return (int)longValue(); }
			@Override public float floatValue() { return longValue(); }
			@Override public double doubleValue() { return 2; }
		};
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,InvalidPeerDataTest.LOCAL);
		config.put(Keywords.STATE,Init.createState(List.of(InvalidPeerDataTest.LOCAL.getAccountKey())));
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,new MemoryStore());
		config.put(Keywords.OUTGOING_CONNECTIONS,outgoing);
		ExpectedLogs logs=ExpectedLogs.carrying(failure).open();
		try (logs) {
			Server peer=API.launchPeer(config);
			try {
				assertTrue(reads.await(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS),"the loop comes round after the failure");
			} finally {
				peer.close();
			}
		}
		logs.assertLogged(Level.ERROR,failure);
	}
}
