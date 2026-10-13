package convex.core;

import static convex.test.Assertions.assertNobodyError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.RecordTest;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;

public class PeerTest {
	static State STATE=InitTest.STATE;

	@Test
	public void testInitial() throws BadSignatureException {
		Peer p = Peer.create(InitTest.FIRST_PEER_KEYPAIR, STATE);

		// initial checks
		long timestamp = p.getTimestamp();
		assertEquals(timestamp, Constants.INITIAL_TIMESTAMP);
		assertSame(STATE, p.getConsensusState());
		assertSame(STATE, p.getGenesisState());

		// Belief check
		AccountKey peerKey = p.getPeerKey();
		Belief b = p.getBelief();
		assertNotNull(b.getOrder(peerKey));

		// check adding a block
		assertEquals(0, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		Block bl0 = Block.of(timestamp);
		p = p.proposeBlock(bl0);

		assertEquals(1, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		// Run a query
		assertEquals(RT.cvm(3L), (p.executeQuery(Reader.read("(+ 1 2)")).getResult()));
		
	}

	@Test
	public void testQuery() throws BadSignatureException {
		Peer p = Peer.create(InitTest.FIRST_PEER_KEYPAIR, STATE);

		assertEquals(RT.cvm(3L),p.executeQuery(Reader.read("(+ 1 2)")).getResult());
		assertEquals(InitTest.HERO,p.executeQuery(Reader.read("*address*"),InitTest.HERO).getResult());

		assertNobodyError(p.executeQuery(Reader.read("(+ 2 3)"),Samples.BAD_ADDRESS).context);

		long limit=1_234;
		ResultContext limited=p.executeQuery(Reader.read("*juice-limit*"),InitTest.HERO,limit);
		assertEquals(CVMLong.create(limit),limited.getResult());
		ResultContext exhausted=p.executeQuery(
			Reader.read("(loop [x 0] (recur (inc x)))"),InitTest.HERO,limit);
		assertEquals(ErrorCodes.JUICE,exhausted.getErrorCode());
	}

	@Test
	public void testQueryAllowanceIndependentOfBalance() {
		Address address=InitTest.HERO;
		for (State initial: new State[] {InitTest.STATE,InitTest.UPGRADED}) {
			for (long balance: new long[] {0,1,1_000_000_000L}) {
				State state=initial.putAccount(address,initial.getAccount(address).withBalance(balance));
				Peer p=Peer.create(InitTest.FIRST_PEER_KEYPAIR,state);
				ResultContext rc=p.executeQuery(Reader.read("[*address* *origin* *balance* *juice-limit*]"),address);
				assertFalse(rc.isError());
				assertEquals(Reader.read("["+address+" "+address+" "+balance+" "+Constants.MAX_TRANSACTION_JUICE+"]"),rc.getResult());
				assertTrue(rc.juiceUsed>0);
				assertEquals(0,rc.totalFees);
				Result result=Result.fromContext(null,rc);
				assertEquals(CVMLong.create(rc.juiceUsed),result.getInfo().get(Keywords.JUICE));
				assertNull(result.getInfo().get(Keywords.FEES));
				assertSame(state,p.getConsensusState());
				assertSame(state,rc.getState());
			}
		}
	}

	@Test
	public void testQueryJuiceBounds() {
		Address address=InitTest.HERO;
		State state=InitTest.UPGRADED.putAccount(address,InitTest.UPGRADED.getAccount(address).withBalance(0));
		Peer p=Peer.create(InitTest.FIRST_PEER_KEYPAIR,state);
		ACell form=Reader.read("*juice-limit*");
		assertEquals(CVMLong.create(1_234),p.executeQuery(form,address,1_234).getResult());
		assertEquals(CVMLong.create(Constants.MAX_TRANSACTION_JUICE),p.executeQuery(form,address,Long.MAX_VALUE).getResult());
		assertThrows(IllegalArgumentException.class,()->p.executeQuery(form,address,-1));
		assertEquals(ErrorCodes.JUICE,p.executeQuery(form,address,0).getErrorCode());
		ResultContext exhausted=p.executeQuery(Reader.read("(loop [] (recur))"),address,1_234);
		assertEquals(ErrorCodes.JUICE,exhausted.getErrorCode());
		assertEquals(1_234,exhausted.juiceUsed);
		assertSame(state,p.getConsensusState());
	}

	@Test
	public void testQueryTemporaryState() {
		Address address=InitTest.HERO;
		State state=InitTest.UPGRADED.putAccount(address,InitTest.UPGRADED.getAccount(address).withBalances(1,0));
		Peer p=Peer.create(InitTest.FIRST_PEER_KEYPAIR,state);
		// Spending all actual funds must not reduce the query's Juice allowance.
		ResultContext rc=p.executeQuery(Reader.read("(do (transfer #0 1) (def query-value 7) [*balance* *juice-limit* query-value])"),address,10_000);
		assertFalse(rc.isError(),()->rc.getResult().toString());
		assertEquals(Reader.read("[0 10000 7]"),rc.getResult());
		assertEquals(0,rc.totalFees);
		assertSame(state,p.getConsensusState());
		assertUndeclaredError(p.executeQuery(Reader.read("query-value"),address).context);
		// Free Juice does not create coins for transfers.
		assertEquals(ErrorCodes.FUNDS,p.executeQuery(Reader.read("(transfer #0 2)"),address).getErrorCode());
	}

	@Test
	public void testQueryTransactionSimulation() {
		Address address=InitTest.HERO;
		State funded=InitTest.UPGRADED;
		State empty=funded.putAccount(address,funded.getAccount(address).withBalance(0));
		Peer p=Peer.create(InitTest.FIRST_PEER_KEYPAIR,empty);
		Invoke tx=Invoke.create(address,empty.getAccount(address).getSequence()+1,"(+ 2 3)");
		assertEquals(ErrorCodes.JUICE,p.executeDetached(tx).getErrorCode());
		assertEquals(ErrorCodes.JUICE,p.executeQuery(tx,address).getErrorCode());
		assertEquals(ErrorCodes.JUICE,p.executeQuery(InitTest.FIRST_PEER_KEYPAIR.signData(tx),address).getErrorCode());
		assertEquals(ErrorCodes.JUICE,empty.applyTransaction(tx).getErrorCode());
		assertSame(empty,p.getConsensusState());

		p=Peer.create(InitTest.FIRST_PEER_KEYPAIR,funded);
		ResultContext simulated=p.executeQuery(tx,address);
		assertFalse(simulated.isError());
		assertEquals(CVMLong.create(5),simulated.getResult());
		assertTrue(simulated.totalFees>0);
		assertEquals(funded.applyTransaction(tx).totalFees,simulated.totalFees);
		assertEquals(ErrorCodes.JUICE,p.executeQuery(tx,address,0).getErrorCode());
		assertSame(funded,p.getConsensusState());
	}

	@Test
	public void testStakeAccess() {
		// use peer address from first peer for testing
		AccountKey pa = InitTest.FIRST_PEER_KEY;
		PeerStatus ps = STATE.getPeer(pa);
		
		long initialStake = ps.getPeerStake();
		assertEquals(initialStake, ps.getTotalStakeShares());

		assertEquals(0, ps.getDelegatedStake(InitTest.HERO));

		// add a delegated stake
		PeerStatus ps2 = ps.withDelegatedStake(InitTest.HERO, 1234);
		assertEquals(1234L, ps2.getDelegatedStake(InitTest.HERO));
		assertEquals(initialStake + 1234, ps2.getTotalStakeShares());
		assertEquals(initialStake, ps2.getPeerStake());
		
		RecordTest.doRecordTests(ps);
		RecordTest.doRecordTests(ps2);
	}
	
	@Test
	public void testForkRecovery() {
		State ST=STATE.withTimestamp(0);
		AKeyPair kp=InitTest.FIRST_PEER_KEYPAIR;
		Address addr=InitTest.FIRST_PEER_ADDRESS;
		AccountKey peerKey=kp.getAccountKey();
		Peer p=Peer.create(kp, ST);
		
		Belief b=p.getBelief();
		assertEquals(0,b.getOrder(peerKey).getBlockCount());
		
		Block b1=Block.of(0, kp.signData(Invoke.create(addr, 1,"(def foo 13)")));
		Order o1=Order.create().append(kp.signData(b1));
		o1=o1.withConsensusPoints(new long[] {1,1,1,1});
		SignedData<Order> so1=kp.signData(o1);
		b=b.withOrders(Index.create(peerKey, so1));
		
		p=p.updateBelief(b);
		assertEquals(ST,p.getConsensusState());
		p=p.updateState();
		Result r=p.getResult(0, 0);
		assertFalse(r.isError());
		assertEquals(CVMLong.create(13),r.getValue());
		assertEquals(CVMLong.create(13),p.executeQuery(Reader.read("foo"), addr).getResult());

		Block b2=Block.of(0, kp.signData(Invoke.create(addr, 1,"(def bar 17)")));
		Order o2=Order.create().append(kp.signData(b2));
		o2=o2.withConsensusPoints(new long[] {1,1,1,1});
		SignedData<Order> so2=kp.signData(o2);
		b=b.withOrders(Index.create(peerKey, so2));

		p=p.updateBelief(b);
		assertUndeclaredError(p.executeQuery(Reader.read("bar"), addr).context);
		
		// Beyond this point, we need to assume fork recovery is enabled
		if (CPoSConstants.ENABLE_FORK_RECOVERY) {
			p=p.updateState();
			assertEquals(CVMLong.create(17),p.executeQuery(Reader.read("bar"), addr).getResult());
			assertUndeclaredError(p.executeQuery(Reader.read("foo"), addr).context);
		}
	}



}
