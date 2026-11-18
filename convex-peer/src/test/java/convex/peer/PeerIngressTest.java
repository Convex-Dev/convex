package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.data.Blobs;

/**
 * Messages received on a peer's network connections are decoded and routed on its
 * ingress workers, not on the network threads.
 */
public class PeerIngressTest {

	private static final long WAIT = 10;
	private static final AKeyPair KP = AKeyPair.createSeeded(608);
	private static final Address HERO = Address.create(Init.GENESIS_ADDRESS);

	/** Counts decodes by whether they run on a network thread: a Netty event loop or an NIO selector. */
	static final class RecordingStore extends MemoryStore {
		final AtomicLong onEventLoop = new AtomicLong();
		final AtomicLong elsewhere = new AtomicLong();

		@Override
		public <T extends ACell> T decodeMultiCell(Blob data) throws BadFormatException {
			// Named by NettyConnection's thread factory, and by the NIO client and server selectors
			String name = Thread.currentThread().getName();
			if (name.startsWith("convex-netty") || name.startsWith("Connection NIO client selector")
					|| name.startsWith("NIO Server loop")) {
				onEventLoop.incrementAndGet();
			} else {
				elsewhere.incrementAndGet();
			}
			return super.decodeMultiCell(data);
		}
	}

	private static Server launch(AStore store) throws Exception {
		HashMap<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.KEYPAIR, KP);
		config.put(Keywords.STATE, Init.createState(List.of(KP.getAccountKey())));
		config.put(Keywords.STORE, store);
		config.put(Keywords.PORT, 0);
		return API.launchPeer(config);
	}

	private static Convex connect(Server server) throws Exception {
		Convex client = Convex.connect(server.getHostAddress(), HERO, KP);
		// Results decode with the client's own store, not the peer's
		client.setStore(new MemoryStore());
		return client;
	}

	@Test
	public void testNoDecodeOnEventLoop() throws Exception {
		RecordingStore store = new RecordingStore();
		Server server = launch(store);
		try (Convex client = connect(server)) {
			CountDownLatch belief = new CountDownLatch(1);
			CountDownLatch data = new CountDownLatch(1);
			server.setMessageReceiveObserver(m -> {
				if (m.getType() == MessageType.BELIEF) belief.countDown();
				if (m.getType() == MessageType.DATA) data.countDown();
			});
			long before = store.elsewhere.get();

			Result q = client.query("(+ 1 2)").get(WAIT, TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3), q.getValue());
			Result t = client.transactSync("(def x 1)");
			assertFalse(t.isError(), t.toString());
			Result s = client.requestStatus().get(WAIT, TimeUnit.SECONDS);
			assertFalse(s.isError(), s.toString());
			client.message(Message.createBelief(server.getBelief()));
			client.message(Message.createDataMessage(List.of(Blobs.createRandom(400)), 1024));
			assertTrue(belief.await(WAIT, TimeUnit.SECONDS));
			assertTrue(data.await(WAIT, TimeUnit.SECONDS));

			assertEquals(0, store.onEventLoop.get(), "no payload decode on a network thread");
			assertTrue(store.elsewhere.get() > before, "payloads are decoded by the peer");
		} finally {
			server.close();
		}
	}

	@Test
	public void testClientDecodesResultsOffEventLoop() throws Exception {
		Server server = launch(new MemoryStore());
		RecordingStore clientStore = new RecordingStore();
		try (Convex client = Convex.connect(server.getHostAddress(), HERO, KP)) {
			client.setStore(clientStore);
			Result q = client.query("(+ 1 2)").get(WAIT, TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3), q.getValue());
			Result t = client.transactSync("(def y 2)");
			assertFalse(t.isError(), t.toString());

			assertEquals(0, clientStore.onEventLoop.get(), "no result decoded on a network thread");
			assertTrue(clientStore.elsewhere.get() >= 2, "results are decoded by the client");
		} finally {
			server.close();
		}
	}

	@Test
	public void testNIOClientDecodesResultsOffSelector() throws Exception {
		Server server = launch(new MemoryStore());
		RecordingStore clientStore = new RecordingStore();
		try (ConvexRemote client = ConvexRemote.connectNIO(server.getHostAddress())) {
			client.setStore(clientStore);
			Result q = client.query("(+ 1 2)").get(WAIT, TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3), q.getValue());
			Result s = client.requestStatus().get(WAIT, TimeUnit.SECONDS);
			assertFalse(s.isError(), s.toString());

			assertEquals(0, clientStore.onEventLoop.get(), "no result decoded on the selector");
			assertTrue(clientStore.elsewhere.get() >= 2, "results are decoded by the client");
		} finally {
			server.close();
		}
	}

	@Test
	public void testHeldDecodeLeavesOtherConnectionsServed() throws Exception {
		Server server = launch(new MemoryStore());
		Keyword held = Keyword.intern("held");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// Runs where the payload was decoded: holding it holds that worker
		server.setMessageReceiveObserver(m -> {
			if (m.getType() != MessageType.QUERY) return;
			AVector<?> v = RT.ensureVector(m.getPayload());
			if ((v == null) || !held.equals(v.get(2))) return;
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try (Convex a = connect(server); Convex b = connect(server)) {
			CompletableFuture<Result> heldResult = a.query(held);
			assertTrue(entered.await(WAIT, TimeUnit.SECONDS));

			Result r = b.query("(+ 2 2)").get(WAIT, TimeUnit.SECONDS);
			assertEquals(CVMLong.create(4), r.getValue());
			assertFalse(heldResult.isDone());

			release.countDown();
			assertEquals(held, heldResult.get(WAIT, TimeUnit.SECONDS).getValue());
		} finally {
			release.countDown();
			server.close();
		}
	}

	/**
	 * A connection the peer has verified as a staked peer's own link is served in the
	 * priority lane: it is answered while ordinary connections hold every worker the
	 * ordinary lane may use.
	 */
	@Test
	public void testVerifiedConnectionServedWhileOrdinaryLaneIsFull() throws Exception {
		AKeyPair peerPair = InboundVerificationTest.LOCAL;
		AccountKey peerKey = peerPair.getAccountKey();
		Server server = InboundVerificationTest.launch(peerPair);
		Keyword held = Keyword.intern("held");
		int ordinaryWorkers = Config.INGRESS_WORKERS - Config.INGRESS_RESERVED_WORKERS;
		CountDownLatch entered = new CountDownLatch(ordinaryWorkers);
		CountDownLatch release = new CountDownLatch(1);
		List<Convex> ordinary = new ArrayList<>();
		try (Convex verified = Convex.connect(server.getHostAddress(), HERO, InboundVerificationTest.REMOTE_A)) {
			// Proves the peer it dialled, then sends a Belief, which has the peer verify it
			assertEquals(peerKey, verified.verifyPeer(peerKey, server.getPeer().getNetworkID()).get(WAIT, TimeUnit.SECONDS));
			verified.setDialledPeer(peerKey);
			CompletableFuture<AConnection> outcome = InboundVerificationTest.nextOutcome(server, true);
			verified.message(Message.createBelief(server.getBelief()));
			assertTrue(outcome.get(WAIT, TimeUnit.SECONDS).isVerified());

			// Runs where the payload was decoded: holding it holds that worker
			server.setMessageReceiveObserver(m -> {
				if (m.getType() != MessageType.QUERY) return;
				AVector<?> v = RT.ensureVector(m.getPayload());
				if ((v == null) || !held.equals(v.get(2))) return;
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			List<CompletableFuture<Result>> heldResults = new ArrayList<>();
			for (int i = 0; i < ordinaryWorkers; i++) {
				Convex c = connect(server);
				ordinary.add(c);
				heldResults.add(c.query(held));
			}
			assertTrue(entered.await(WAIT, TimeUnit.SECONDS));

			Result r = verified.query("(+ 2 2)").get(WAIT, TimeUnit.SECONDS);
			assertEquals(CVMLong.create(4), r.getValue());
			for (CompletableFuture<Result> f : heldResults) assertFalse(f.isDone());
		} finally {
			release.countDown();
			for (Convex c : ordinary) c.close();
			server.close();
		}
	}

	@Test
	public void testControlMessagesAnswered() throws Exception {
		Server server = launch(new MemoryStore());
		try (Convex client = connect(server)) {
			Result s = client.requestStatus().get(WAIT, TimeUnit.SECONDS);
			assertFalse(s.isError(), s.toString());
			assertEquals(server.getPeerKey(), client.verifyPeer(server.getPeerKey()).get(WAIT, TimeUnit.SECONDS));
		} finally {
			server.close();
		}
	}
}
