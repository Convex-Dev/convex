package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.peer.Config;

/**
 * Tests for Convex Direct client
 */
public class ConvexDirectTest {
	static final AKeyPair peerKey=AKeyPair.createSeeded(5675675);
	static final AKeyPair otherKey=AKeyPair.createSeeded(8686868);

	private static ConvexDirect createSingle() {
		State state=Init.createTestState(List.of(peerKey.getAccountKey()));
		return ConvexDirect.create(peerKey,state);
	}

	@Test public void testSetup() throws InterruptedException {
		ConvexDirect convex=createSingle();
		Address addr=convex.getAddress();

		assertTrue(convex.isConnected());
		assertEquals(Init.GENESIS_PEER_ADDRESS,addr);

		assertEquals(addr,convex.query("*address*").join().getValue());

		Result r=convex.transactSync("(+ 1 2)");
		assertFalse(r.isError(),()->"Expected no error but got: "+r);
		assertEquals(CVMLong.create(3),r.getValue());
	}

	@Test public void testStatusPingAndState() throws Exception {
		ConvexDirect convex=createSingle();

		// STATUS answers with the same vector a Peer server would return
		Result status=convex.requestStatus().join();
		assertFalse(status.isError(),()->status.toString());
		AVector<ACell> v=RT.ensureVector(status.getValue());
		assertNotNull(v);
		assertEquals(Config.STATUS_COUNT,v.count());
		assertEquals(peerKey.getAccountKey(),v.get(3));
		assertEquals(convex.getPeer().getConsensusState().getHash(),v.get(4));

		CVMLong ts=convex.ping().join();
		assertNotNull(ts);
		assertTrue(ts.longValue()>0);

		// The consensus State comes straight from the in-memory Peer, no store needed
		assertSame(convex.getPeer().getConsensusState(),convex.acquireState().join());
	}

	@Test public void testMessageRawRoundTrip() throws Exception {
		ConvexDirect convex=createSingle();

		// Encoded messages take the same path as decoded ones
		Message ping=Message.createPing(17);
		Result r=convex.messageRaw(ping.getMessageData()).join();
		assertFalse(r.isError(),()->r.toString());
		assertEquals(CVMLong.create(17),r.getID());
		assertNotNull(RT.ensureLong(r.getValue()));

		// A message a direct client cannot serve is a definite error, never null
		Result bad=convex.message(Message.create(MessageType.BELIEF,Strings.create("junk"))).join();
		assertNotNull(bad);
		assertTrue(bad.isError());
	}

	@Test public void testBeliefExchangeReachesConsensus() throws Exception {
		State state=Init.createTestState(List.of(peerKey.getAccountKey(),otherKey.getAccountKey()));
		ConvexDirect a=ConvexDirect.create(peerKey,state);
		ConvexDirect b=ConvexDirect.create(otherKey,state);
		AccountKey aKey=peerKey.getAccountKey();

		// A holds half the stake, so its Block is proposed but cannot be finalised alone
		a.transactSync("(+ 1 2)");
		assertEquals(1,a.getPeer().getPeerOrder().getBlockCount());
		assertNull(a.getPeer().getResult(0,0));
		assertNull(b.getPeer().getBelief().getOrder(aKey));

		// B receives A's Belief exactly as a Peer would, and merges it
		assertTrue(b.trySend(Message.createBelief(a.getPeer().getBelief())));
		Order merged=b.getPeer().getBelief().getOrder(aKey);
		assertNotNull(merged);
		assertEquals(1,merged.getBlockCount());

		// Exchanging Beliefs both ways brings the two-peer network to consensus
		Result executed=null;
		for (int i=0; i<10 && executed==null; i++) {
			assertTrue(a.trySend(Message.createBelief(b.getPeer().getBelief())));
			assertTrue(b.trySend(Message.createBelief(a.getPeer().getBelief())));
			executed=a.getPeer().getResult(0,0);
		}
		assertNotNull(executed,"Two-peer network did not reach consensus by direct Belief exchange");
		assertEquals(CVMLong.create(3),executed.getValue());
	}

	@Test public void testDataRequestAndAcquire() throws Exception {
		ConvexDirect convex=createSingle();
		MemoryStore peerStore=new MemoryStore();
		convex.setStore(peerStore);
		ACell value=Cells.persist(Strings.create("direct data"),peerStore);
		Hash h=value.getHash();

		// DATA_REQUEST is answered from the client's store
		Result r=convex.message(Message.createDataRequest(CVMLong.create(5),h)).join();
		assertFalse(r.isError(),()->r.toString());
		AVector<ACell> cells=RT.ensureVector(r.getValue());
		assertNotNull(cells);
		boolean found=false;
		for (int i=0; i<cells.count(); i++) {
			if (value.equals(cells.get(i))) found=true;
		}
		assertTrue(found,()->"Requested value missing from "+cells);

		// acquire copies the value into the destination store
		MemoryStore target=new MemoryStore();
		assertEquals(value,convex.acquire(h,target).join());
		assertNotNull(target.refForHash(h));

		// and fails definitely for a hash the client does not hold
		Hash missing=Strings.create("never stored").getHash();
		assertThrows(CompletionException.class,()->convex.acquire(missing,target).join());
	}

	@Test public void testCloseDisconnects() throws Exception {
		ConvexDirect convex=createSingle();
		assertTrue(convex.isConnected());
		convex.close();
		assertFalse(convex.isConnected());
		assertFalse(convex.trySend(Message.createPing(1)));
		Result r=convex.message(Message.createPing(1)).join();
		assertNotNull(r);
		assertTrue(r.isError());

		convex.reconnect();
		assertTrue(convex.isConnected());
		assertNotNull(convex.ping().join());
	}
}
