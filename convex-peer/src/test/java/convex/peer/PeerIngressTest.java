package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.lang.RT;
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

	/** Counts decodes by whether they run on a network event loop. */
	static final class RecordingStore extends MemoryStore {
		final AtomicLong onEventLoop = new AtomicLong();
		final AtomicLong elsewhere = new AtomicLong();

		@Override
		public <T extends ACell> T decodeMultiCell(Blob data) throws BadFormatException {
			// The event loops' threads are named by NettyConnection's thread factory
			if (Thread.currentThread().getName().startsWith("convex-netty")) {
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
