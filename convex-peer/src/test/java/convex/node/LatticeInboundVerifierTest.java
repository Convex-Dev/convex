package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.store.MemoryStore;

/** Closing the inbound verifier ends the attempts under way and starts no more until it is opened again. */
public class LatticeInboundVerifierTest {
	private static final AccountKey CLAIMED=AKeyPair.createSeeded(170).getAccountKey();

	/** An inbound connection that accepts every message. */
	private static final class OpenConnection extends AConnection {
		volatile boolean closed;
		@Override public boolean sendMessage(Message message) { return !closed; }
		@Override public boolean trySendMessage(Message message) { return !closed; }
		@Override public InetSocketAddress getRemoteAddress() { return null; }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed=true; }
		@Override public long getReceivedCount() { return 0; }
	}

	@Test
	public void closeWaitsForAnAttemptUnderWay() throws Exception {
		Thread closer=Thread.currentThread();
		CountDownLatch entered=new CountDownLatch(1);
		AtomicBoolean closing=new AtomicBoolean();
		AtomicBoolean closeReturned=new AtomicBoolean();
		AtomicBoolean ended=new AtomicBoolean();
		LatticeInboundVerifier verifier=new LatticeInboundVerifier(() -> {
			entered.countDown();
			// Ends only once the close waits for it, so a close that does not wait finds it running
			while (!(closing.get() && closer.getState()==Thread.State.TIMED_WAITING) && !closeReturned.get()) {
				Thread.onSpinWait();
			}
			ended.set(true);
			return null;
		});
		try (LatticePropagator owner=new LatticePropagator(new MemoryStore())) {
			verifier.maybeStart(new OpenConnection(),owner,CLAIMED);
			assertTrue(entered.await(5,TimeUnit.SECONDS));
			closing.set(true);
			verifier.close();
			boolean waited=ended.get();
			closeReturned.set(true);
			assertTrue(waited,"the attempt ended before close returned");
		} finally {
			closeReturned.set(true);
		}
	}

	@Test
	public void aClosedVerifierStartsNoAttemptUntilOpened() throws Exception {
		CountDownLatch release=new CountDownLatch(1);
		AtomicInteger attempts=new AtomicInteger();
		LatticeInboundVerifier verifier=new LatticeInboundVerifier(() -> {
			attempts.incrementAndGet();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		});
		try (LatticePropagator owner=new LatticePropagator(new MemoryStore())) {
			verifier.close();
			verifier.maybeStart(new OpenConnection(),owner,CLAIMED);
			assertTrue(verifier.claimedKeys().isEmpty(),"no attempt is under way");
			assertEquals(0,attempts.get());

			// Opened again, as a group starting again opens it
			verifier.open();
			verifier.maybeStart(new OpenConnection(),owner,CLAIMED);
			assertEquals(Set.of(CLAIMED),verifier.claimedKeys());
		} finally {
			release.countDown();
			verifier.close();
		}
	}
}
