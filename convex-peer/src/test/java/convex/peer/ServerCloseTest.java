package convex.peer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.init.Init;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;

/**
 * Tests that closing a peer finishes every step of its shutdown when one of them
 * fails: the components stop, the final checkpoint is written, the store it owns is
 * closed and waiting for shutdown returns.
 */
public class ServerCloseTest {
	static final AKeyPair KP=AKeyPair.createSeeded(160);
	static final AKeyPair REMOTE=AKeyPair.createSeeded(161);
	static final Duration WAIT=Duration.ofSeconds(InvalidPeerDataTest.WAIT);

	/** A store that counts root writes made after it is armed, and its closes. */
	static final class TrackingStore extends MemoryStore {
		volatile boolean armed;
		volatile RuntimeException closeFailure;
		final AtomicInteger rootWrites=new AtomicInteger();
		final AtomicInteger closes=new AtomicInteger();

		@Override
		public <T extends ACell> Ref<T> setRootData(T data) {
			if (armed) rootWrites.incrementAndGet();
			return super.setRootData(data);
		}

		@Override
		public void close() {
			closes.incrementAndGet();
			super.close();
			RuntimeException failure=closeFailure;
			if (failure!=null) throw failure;
		}
	}

	/** A peer connection whose GOODBYE send fails. */
	static final class FailingGoodbye extends Convex {
		final IllegalStateException failure=new IllegalStateException("simulated GOODBYE failure");
		final AtomicInteger closes=new AtomicInteger();

		FailingGoodbye() { super(null,null); }

		@Override public boolean trySend(Message message) {
			if (message.getType()==MessageType.GOODBYE) throw failure;
			return true;
		}
		@Override public boolean isConnected() { return closes.get()==0; }
		@Override public CompletableFuture<Result> transact(SignedData<ATransaction> tx) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public CompletableFuture<Result> messageRaw(Blob message) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public CompletableFuture<Result> message(Message message) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public <T extends ACell> CompletableFuture<T> acquire(Hash hash,AStore store) {
			return new CompletableFuture<>();
		}
		@Override public CompletableFuture<Result> requestStatus() {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public CompletableFuture<Result> query(ACell query,Address address) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}
		@Override public void close() { closes.incrementAndGet(); }
		@Override public InetSocketAddress getHostAddress() { return null; }
		@Override public void reconnect() {}
		@Override public String toString() { return "Failing GOODBYE test connection"; }
	}

	/** Creates a peer that owns the given store, as one that opened its own store does. */
	static Server createOwning(TrackingStore store, Map<Keyword,Object> extra) throws Exception {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,KP);
		config.put(Keywords.STATE,Init.createState(List.of(KP.getAccountKey())));
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,store);
		config.put(Keywords.POLL_DELAY,InvalidPeerDataTest.LONG_POLL_DELAY);
		config.putAll(extra);
		Config.ensureFlags(config);
		Config.ensurePeerKey(config);
		Config.ensureGenesisState(config);
		return Server.create(config,true,false);
	}

	static Server launchWithFailingGoodbye(TrackingStore store, FailingGoodbye connection) throws Exception {
		Server peer=createOwning(store,Map.of());
		peer.launch();
		assertTrue(peer.getConnectionManager().addConnection(REMOTE.getAccountKey(),connection));
		store.armed=true;
		return peer;
	}

	static void assertShutDownCompletely(Server peer, TrackingStore store, FailingGoodbye connection) {
		assertTimeoutPreemptively(WAIT,peer::waitForShutdown);
		assertFalse(peer.isRunning());
		for (AThreadedComponent component: List.of(peer.propagator,peer.queryHandler,
				peer.transactionHandler,peer.executor)) {
			assertFalse(component.thread.isAlive(),component.getThreadName()+" has stopped");
		}
		assertTrue(store.rootWrites.get()>0,"the final checkpoint is written");
		assertEquals(1,store.closes.get(),"the owned store is closed");
		assertEquals(1,connection.closes.get(),"the connection is closed");
	}

	@Test
	public void aFailedGoodbyeStillCompletesTheShutdown() throws Exception {
		TrackingStore store=new TrackingStore();
		FailingGoodbye connection=new FailingGoodbye();
		Server peer=launchWithFailingGoodbye(store,connection);
		try {
			assertSame(connection.failure,assertThrows(IllegalStateException.class,peer::close),
				"close reports the failure once it has finished");
			assertShutDownCompletely(peer,store,connection);

			peer.close();
			assertEquals(1,store.closes.get(),"a second close does nothing more");
		} finally {
			peer.close();
		}
	}

	@Test
	public void aRequestedShutdownWithAFailedGoodbyeStillCompletes() throws Exception {
		TrackingStore store=new TrackingStore();
		FailingGoodbye connection=new FailingGoodbye();
		Server peer=launchWithFailingGoodbye(store,connection);
		try {
			peer.requestShutdown("test",new IllegalStateException("simulated"),true);
			assertShutDownCompletely(peer,store,connection);
		} finally {
			peer.close();
		}
	}

	@Test
	public void aFailedLaunchKeepsItsFailureAndClosesThePeer() throws Exception {
		TrackingStore store=new TrackingStore();
		IllegalStateException closeFailure=new IllegalStateException("simulated store close failure");
		store.closeFailure=closeFailure;
		Server peer=createOwning(store,Map.of(Config.TLS_PORT,-1));
		try {
			LaunchException thrown=assertThrows(LaunchException.class,peer::launch);
			assertTrue(thrown.getCause() instanceof ConfigException,thrown.toString());
			assertArrayEquals(new Throwable[] {closeFailure},thrown.getSuppressed());
			assertEquals(1,store.closes.get());
			assertTimeoutPreemptively(WAIT,peer::waitForShutdown);

			assertThrows(LaunchException.class,peer::launch,"a closed peer does not launch again");
		} finally {
			peer.close();
		}
	}
}
