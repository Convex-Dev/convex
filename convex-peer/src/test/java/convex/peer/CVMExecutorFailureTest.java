package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;

/**
 * Tests that the CVM executor shuts the peer down only when it cannot advance
 * consensus state, writing the final checkpoint as it does, and that running out of
 * memory or a failing observer costs nothing more than the work it interrupted. Each
 * failure is simulated: no test exhausts real memory or stack.
 */
public class CVMExecutorFailureTest {
	static final AKeyPair LOCAL=InvalidPeerDataTest.LOCAL;
	static final Address ORIGIN=Init.GENESIS_ADDRESS;

	/** A store whose root write fails once with a chosen failure, counting the writes after it. */
	static final class RootFailingStore extends MemoryStore {
		final AtomicReference<Throwable> failure=new AtomicReference<>();
		final AtomicInteger writesAfterFailure=new AtomicInteger();
		volatile boolean failed;

		@Override
		public <T extends ACell> Ref<T> setRootData(T data) {
			Throwable t=failure.getAndSet(null);
			if (t!=null) {
				failed=true;
				throw Utils.<RuntimeException>sneakyThrow(t);
			}
			if (failed) writesAfterFailure.incrementAndGet();
			return super.setRootData(data);
		}
	}

	static SignedData<ATransaction> transaction(String source) {
		return LOCAL.signData(Invoke.create(ORIGIN,1,source));
	}

	/**
	 * A failure advancing consensus state, other than running out of memory, shuts the
	 * peer down, and the final checkpoint is still written.
	 */
	@Test
	public void aFailureAdvancingStateShutsThePeerDownWithACheckpoint() throws Exception {
		// An Error and an exception: every failure but running out of memory or a failure
		// of the JVM itself takes the same path. A stack overflow is a VirtualMachineError
		// that is not a JVM failure.
		for (Throwable failure: List.of(new StackOverflowError("simulated"),new IllegalStateException("simulated"))) {
			ExpectedLogs logs=ExpectedLogs.carrying(failure).open();
			try (logs) {
				RootFailingStore store=new RootFailingStore();
				Server peer=InvalidPeerDataTest.launchPeer(store,LOCAL);
				try {
					store.failure.set(failure);
					// Executing it advances the state, which is then persisted
					Convex.connect(peer,ORIGIN,LOCAL).transact(transaction("(+ 1 2)"));
					// Preemptive: without the shutdown, the wait would never return
					assertTimeoutPreemptively(Duration.ofSeconds(InvalidPeerDataTest.WAIT),() -> peer.waitForShutdown(),failure.toString());
					assertFalse(peer.isRunning(),failure.toString());
					assertTrue(store.writesAfterFailure.get()>0,failure+": the final checkpoint is written");
				} finally {
					peer.close();
				}
			}
			logs.assertLogged(Level.ERROR,failure);
		}
	}

	/** Running out of memory advancing consensus state is retried: the peer carries on. */
	@Test
	public void runningOutOfMemoryAdvancingStateIsRetried() throws Exception {
		OutOfMemoryError failure=new OutOfMemoryError("simulated");
		ExpectedLogs logs=ExpectedLogs.carrying(failure).open();
		try (logs) {
			RootFailingStore store=new RootFailingStore();
			Server peer=InvalidPeerDataTest.launchPeer(store,LOCAL);
			try {
				store.failure.set(failure);
				Result r=Convex.connect(peer,ORIGIN,LOCAL).transact(transaction("(+ 1 2)")).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				assertEquals(CVMLong.create(3),r.getValue(),r.toString());
				assertTrue(store.failed,"the root write failed once");
				// Before the result is reported, so already done
				assertTrue(store.writesAfterFailure.get()>0,"the advanced state is persisted on the retry");
				assertTrue(peer.isRunning());
			} finally {
				peer.close();
			}
		}
		logs.assertLogged(Level.WARN,failure);
	}

	/**
	 * A state observer that fails, on registration or on an update, costs nothing:
	 * other observers are called and clients get their results.
	 */
	@Test
	public void aFailingStateObserverCostsNothing() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			peer.addStateUpdateObserver(p -> {throw new NoClassDefFoundError("simulated");});
			AtomicInteger updates=new AtomicInteger();
			peer.addStateUpdateObserver(p -> updates.incrementAndGet());
			int registered=updates.get();
			Result r=Convex.connect(peer,ORIGIN,LOCAL).transact(transaction("(+ 1 2)")).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3),r.getValue(),r.toString());
			assertTrue(updates.get()>registered,"the other observer is called on the update");
			assertTrue(peer.isRunning());
		} finally {
			peer.close();
		}
	}
}
