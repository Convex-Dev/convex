package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;

/**
 * Tests that a peer contains the failure of one external request, whatever is thrown,
 * unless the JVM itself has failed. Each failure is simulated: no test exhausts real
 * memory or stack.
 */
public class PeerRequestFailureTest {
	static final AKeyPair LOCAL=InvalidPeerDataTest.LOCAL;
	static final Address ORIGIN=Init.GENESIS_ADDRESS;

	/** Failures of one request that a peer contains: the JVM itself has not failed. */
	static final List<Error> CONTAINED=List.of(new StackOverflowError("simulated"),
		new OutOfMemoryError("simulated"),new NoClassDefFoundError("simulated"),
		new AssertionError("simulated"));

	/** A client connection that records results, or throws a chosen Error on every reply. */
	static final class ReplyConnection extends AConnection {
		final Error error;
		final ConcurrentHashMap<ACell,Result> results=new ConcurrentHashMap<>();
		volatile boolean closed;

		ReplyConnection(Error error) {
			this.error=error;
		}

		Result result(long id) {
			return results.get(CVMLong.create(id));
		}

		@Override public boolean sendMessage(Message msg) {
			return trySendMessage(msg);
		}

		@Override public boolean trySendMessage(Message msg) {
			if (error!=null) throw error;
			if (msg.getType()==MessageType.RESULT) {
				Result r=msg.toResult();
				results.put(r.getID(),r);
			}
			return true;
		}

		@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.0.2.3",30000); }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed=true; }
		@Override public long getReceivedCount() { return 0; }
	}

	/**
	 * A query whose handling fails, here as its result is sent, fails alone: its
	 * connection is closed and the next query in the same batch is answered.
	 */
	@Test
	public void aQueryThatFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			for (Error error: CONTAINED) {
				// Not started: this test thread runs it
				QueryHandler handler=new QueryHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				assertTrue(handler.offerQuery(Message.createQuery(1,"(+ 1 2)",ORIGIN).withConnection(failing)));
				assertTrue(handler.offerQuery(Message.createQuery(2,"(+ 2 3)",ORIGIN).withConnection(healthy)));
				handler.loop();
				assertEquals(CVMLong.create(5),healthy.result(2).getValue(),error.toString());
				assertTrue(failing.isClosed(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A data request whose handling fails, here as its response is sent, fails alone:
	 * its connection stays open and the next request in the same batch is answered.
	 */
	@Test
	public void aDataRequestThatFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			for (Error error: CONTAINED) {
				QueryHandler handler=new QueryHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				Hash held=peer.getBelief().getHash();
				assertTrue(handler.offerQuery(Message.createDataRequest(CVMLong.create(1),held).withConnection(failing)));
				assertTrue(handler.offerQuery(Message.createQuery(2,"(+ 2 3)",ORIGIN).withConnection(healthy)));
				handler.loop();
				assertEquals(CVMLong.create(5),healthy.result(2).getValue(),error.toString());
				assertFalse(failing.isClosed(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/** A failure of the JVM itself while answering a query is not contained. */
	@Test
	public void aJvmFailureAnsweringAQueryIsNotContained() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			QueryHandler handler=new QueryHandler(peer);
			ReplyConnection failing=new ReplyConnection(new InternalError("simulated"));
			assertTrue(handler.offerQuery(Message.createQuery(1,"(+ 1 2)",ORIGIN).withConnection(failing)));
			assertThrows(InternalError.class,handler::loop);
		} finally {
			peer.close();
		}
	}
}
