package convex.peer;

import static convex.peer.InvalidPeerDataTest.LOCAL;
import static convex.peer.InvalidPeerDataTest.REMOTE_A;
import static convex.peer.InvalidPeerDataTest.REMOTE_B;
import static convex.peer.InvalidPeerDataTest.blockTime;
import static convex.peer.InvalidPeerDataTest.checkGoodOrderMergesBeside;
import static convex.peer.InvalidPeerDataTest.WAIT;
import static convex.peer.InvalidPeerDataTest.block;
import static convex.peer.InvalidPeerDataTest.launchPeer;
import static convex.peer.InvalidPeerDataTest.order;
import static convex.peer.InvalidPeerDataTest.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.data.prim.CVMLong;

/**
 * A staked peer's Order whose Blocks the merge cannot read does not stop the merge of
 * a good Order beside it.
 */
public class MalformedOrderTest {

	/** An Order with any values as its Blocks. */
	@SuppressWarnings({"unchecked","rawtypes"})
	static SignedData<Order> orderOf(AKeyPair kp, long timestamp, ACell... entries) {
		Order order=Order.create().withBlocks((AVector)Vectors.of((Object[])entries));
		return kp.signData(order.withTimestamp(timestamp));
	}

	/**
	 * After A's good Order is merged, A sends a newer Order the merge cannot read beside
	 * B's good Order: B's Order is merged and A's earlier Order is kept.
	 */
	private static void checkEarlierOrderKept(Server peer, SignedData<Order> newer) throws Exception {
		BlockingQueue<Belief> updates=InvalidPeerDataTest.ownOrderUpdates(peer);
		long ts=blockTime(peer);
		LocalConnection senderEnd=InvalidPeerDataTest.connect(peer);
		SignedData<Order> first=order(REMOTE_A,1,block(REMOTE_A,ts));
		senderEnd.sendMessage(wire(Message.createBelief(Belief.create(first))));
		assertNotNull(updates.poll(WAIT,TimeUnit.SECONDS),"A's first Order is merged");

		SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));
		senderEnd.sendMessage(wire(Message.createBelief(Belief.create(newer,good))));
		Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
		assertNotNull(merged,"the good Order is merged");
		assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
		assertEquals(first,merged.getOrders().get(REMOTE_A.getAccountKey()));
		assertTrue(peer.isRunning());
	}

	@Test
	public void aNewerOrderWhoseBlocksAreNotAVector() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			Order order=Order.create(Vectors.of(CVMLong.create(2),Vectors.createLongs(1,0,0,0),CVMLong.ONE));
			checkEarlierOrderKept(peer,REMOTE_A.signData(order));
		} finally {
			peer.close();
		}
	}

	@Test
	public void aNewerOrderWithAnUnsignedBlock() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			checkEarlierOrderKept(peer,orderOf(REMOTE_A,2,CVMLong.ONE));
		} finally {
			peer.close();
		}
	}

	@Test
	public void aNewerOrderWithASignedValueThatIsNotABlock() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			checkEarlierOrderKept(peer,orderOf(REMOTE_A,2,REMOTE_A.signData(CVMLong.ONE)));
		} finally {
			peer.close();
		}
	}

	@Test
	public void aNewerOrderWithABlockWhoseTransactionsAreNotAVector() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			Block malformed=Block.create(Vectors.of(CVMLong.create(blockTime(peer)),CVMLong.ONE));
			checkEarlierOrderKept(peer,orderOf(REMOTE_A,2,REMOTE_A.signData(malformed)));
		} finally {
			peer.close();
		}
	}

	/** A staked peer's Order whose Blocks are not a vector. */
	@Test
	public void anOrderWhoseBlocksAreNotAVector() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			Order order=Order.create(Vectors.of(CVMLong.create(1),Vectors.createLongs(1,0,0,0),CVMLong.ONE));
			checkGoodOrderMergesBeside(peer,REMOTE_A.signData(order));
		} finally {
			peer.close();
		}
	}

	/** A staked peer's Order with a Block entry that is not a signed value. */
	@Test
	public void anOrderWithAnUnsignedBlock() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			checkGoodOrderMergesBeside(peer,orderOf(REMOTE_A,1,CVMLong.ONE));
		} finally {
			peer.close();
		}
	}

	/** A staked peer's Order with a Block entry whose signed value is not a Block. */
	@Test
	public void anOrderWithASignedValueThatIsNotABlock() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			checkGoodOrderMergesBeside(peer,orderOf(REMOTE_A,1,REMOTE_A.signData(CVMLong.ONE)));
		} finally {
			peer.close();
		}
	}

	/** A staked peer's Order with a Block whose transactions are not a vector. */
	@Test
	public void anOrderWithABlockWhoseTransactionsAreNotAVector() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			Block malformed=Block.create(Vectors.of(CVMLong.create(blockTime(peer)),CVMLong.ONE));
			checkGoodOrderMergesBeside(peer,orderOf(REMOTE_A,1,REMOTE_A.signData(malformed)));
		} finally {
			peer.close();
		}
	}
}
