package convex.core;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.CPoSConstants;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Juice;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitTest;
import convex.core.lang.Reader;
import convex.core.lang.TestState;

/**
 * Tests for State transition scenarios
 */
public class StateTransitionsTest {

	final AKeyPair KEYPAIR_A = AKeyPair.createSeeded(1001);
	final AKeyPair KEYPAIR_B = AKeyPair.createSeeded(1002);
	final AKeyPair KEYPAIR_C = AKeyPair.createSeeded(1003);
	final AKeyPair KEYPAIR_NIKI = AKeyPair.createSeeded(1004);
	final AKeyPair KEYPAIR_ROBB = AKeyPair.createSeeded(1005);

	final AKeyPair KEYPAIR_PEER = InitTest.FIRST_PEER_KEYPAIR;
	final AccountKey FIRST_PEER_KEY=KEYPAIR_PEER.getAccountKey();

	final Address REWARD_POOL = Address.create(0); // reward pool account
	final Address ADDRESS_A = Address.create(1); // initial account, also Peer
	final Address ADDRESS_B = Address.create(2); // initial account
	final Address ADDRESS_ROBB = Address.create(3); // initial account
	
	// extra accounts to add later
	final Address ADDRESS_C = Address.create(4);
	final Address ADDRESS_NIKI = Address.create(5);
	
	final long ABAL=100000;
	final long BBAL=20000;

	@Test
	public void testAccountTransfers() throws BadSignatureException {
		AccountKey ka=KEYPAIR_A.getAccountKey();
		AccountKey kb=KEYPAIR_B.getAccountKey();
		long STAKE=CPoSConstants.MINIMUM_EFFECTIVE_STAKE*10;
		AVector<AccountStatus> accounts = Vectors.of(
				AccountStatus.create(0,null).withMemory(0),
				AccountStatus.create(ABAL,ka).withMemory(10000),
				AccountStatus.create(BBAL,kb).withMemory(10000),
				AccountStatus.create(Constants.MAX_SUPPLY - STAKE - ABAL - BBAL,KEYPAIR_ROBB.getAccountKey()).withMemory(10000)
		// No account for C yet
		);
		State s = State.EMPTY.withAccounts(accounts); // don't need any peers for these tests
		s=s.withPeer(ka, PeerStatus.create(ADDRESS_A, STAKE));
		
		s=s.updateMemoryPool(0, 0); // clear memory pool so doesn't confuse things
		assertEquals(Constants.MAX_SUPPLY, s.computeTotalBalance());

		assertEquals(ABAL, s.getBalance(ADDRESS_A));
		assertEquals(BBAL, s.getBalance(ADDRESS_B));
		assertNull(s.getBalance(ADDRESS_C));

		long JPRICE=s.getJuicePrice().longValue(); // Juice price
		long AMT=50; // Amount for small transfers
		
		{ // transfer from existing to existing account A->B
			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_B, AMT);
			long TCOST = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t1)) * JPRICE;

			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			long nowTS = Constants.INITIAL_TIMESTAMP;
			Block b = Block.of(nowTS, st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			BlockResult br = s.applyBlock(sb);
			AVector<Result> results = br.getResults();
			assertEquals(1, results.count());
			assertNull(br.getErrorCode(0),br.getResult(0).toString()); // should be null for successful transfer transaction
			State s2 = br.getState();
			assertEquals(ABAL - TCOST - AMT, s2.getBalance(ADDRESS_A));
			assertEquals(BBAL + AMT, s2.getBalance(ADDRESS_B));
			assertCVMEquals(nowTS, s2.getTimestamp());
		}

		{ // transfer from existing to non-existing account A -> C
			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_C, AMT);
			long TCOST = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t1)) * JPRICE;
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			State s2 = s.applyBlock(sb).getState();

			// no transfer should have happened, although cost should have been paid
			assertEquals(ABAL - TCOST, s2.getBalance(ADDRESS_A));
			assertNull(s2.getBalance(ADDRESS_C));
		}

		{ // transfer from a non-existent address
			Transfer t1 = Transfer.create(ADDRESS_C,1, ADDRESS_B, AMT);
			SignedData<ATransaction> st = KEYPAIR_C.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			BlockResult br=s.applyBlock(sb);
			assertEquals(ErrorCodes.NOBODY, br.getResult(0).getErrorCode());

		}

		{ // transfer from existing to new account A -> C
			// First create new account C
			State s0=s.putAccount(ADDRESS_C, AccountStatus.create(0L,KEYPAIR_C.getAccountKey()));

			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_C, AMT);
			long TCOST = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t1)) * JPRICE;
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			State s2 = s0.applyBlock(sb).getState();

			// Transfer should have happened
			assertEquals(ABAL - TCOST - AMT, s2.getBalance(ADDRESS_A));
			assertEquals(AMT, s2.getBalance(ADDRESS_C));
		}

		{ // two transfers in sequence, both from A -> C
			// First create new account C
			State s0=s.putAccount(ADDRESS_C, AccountStatus.create(0L,KEYPAIR_C.getAccountKey()));

			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_C, AMT*3);
			long TCOST1 = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t1)) * JPRICE;
			SignedData<ATransaction> st1 = KEYPAIR_A.signData(t1);
			Transfer t2 = Transfer.create(ADDRESS_A,2, ADDRESS_C, AMT*2);
			long TCOST2 = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t2)) * JPRICE;
			SignedData<ATransaction> st2 = KEYPAIR_A.signData(t2);
			Block b = Block.of(System.currentTimeMillis(), st1, st2);
			SignedData<Block> sb=KEYPAIR_A.signData(b);

			BlockResult br = s0.applyBlock(sb);
			State s2 = br.getState();
			assertEquals(ABAL - AMT*5 - (TCOST1+TCOST2), s2.getBalance(ADDRESS_A));
			assertEquals(BBAL, s2.getBalance(ADDRESS_B));
			assertEquals(AMT*5, s2.getBalance(ADDRESS_C));
		}

		{ // two transfers in sequence, 2 different accounts A B --> new account C
			// First create new account C
			State s0=s.putAccount(ADDRESS_C, AccountStatus.create(0L,KEYPAIR_C.getAccountKey()));

			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_C, AMT);
			long TCOST1 = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t1)) * JPRICE;
			SignedData<ATransaction> st1 = KEYPAIR_A.signData(t1);
			Transfer t2 = Transfer.create(ADDRESS_B,1, ADDRESS_C, AMT);
			long TCOST2 = (Juice.TRANSACTION + Juice.TRANSFER+Juice.priceMemorySize(t2)) * JPRICE;
			SignedData<ATransaction> st2 = KEYPAIR_B.signData(t2);
			Block b = Block.of(System.currentTimeMillis(), st1, st2);
			SignedData<Block> sb=KEYPAIR_A.signData(b);

			BlockResult br = s0.applyBlock(sb);
			State s2 = br.getState();
			assertEquals(ABAL - AMT - TCOST1, s2.getBalance(ADDRESS_A));
			assertEquals(BBAL - AMT - TCOST2, s2.getBalance(ADDRESS_B));
			assertEquals(2*AMT, s2.getBalance(ADDRESS_C));

			AVector<Result> results = br.getResults();
			assertEquals(2, results.count());
			assertCVMEquals(50L,br.getResult(0).getValue()); // result for successful transfer
			assertEquals(Constants.MAX_SUPPLY, br.getState().computeTotalBalance());
		}

		{ // transfer with an incorrect sequence number
			Transfer t1 = Transfer.create(ADDRESS_A,2, ADDRESS_C, AMT);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			BlockResult br = s.applyBlock(sb);
			AVector<Result> results = br.getResults();
			assertEquals(1, results.count());
			assertEquals(ErrorCodes.SEQUENCE, br.getResult(0).getErrorCode());
		}

		{ // transfer amount greater than current balance
			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_C, 10*ABAL);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			BlockResult br = s.applyBlock(sb);
			assertEquals(ErrorCodes.FUNDS, br.getResult(0).getErrorCode());

			State newState = br.getState();
			assertEquals(Constants.MAX_SUPPLY, newState.computeTotalBalance());
		}



		{ // sending money to NIKI, a new account
			// Two new Accounts
			State s0=s.putAccount(ADDRESS_C, AccountStatus.create(0L,KEYPAIR_C.getAccountKey()));
			s0=s0.putAccount(ADDRESS_NIKI, AccountStatus.create(0L,KEYPAIR_NIKI.getAccountKey()));

			// System.out.println(ADDRESS_NIKI);
			// System.out.println("Niki has "+s.getBalance(ADDRESS_NIKI).getValue());

			// System.out.println("Tansferring "+AMT+" to Niki");

			Transfer t1 = Transfer.create(ADDRESS_A,1, ADDRESS_NIKI, AMT);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			SignedData<Block> sb=KEYPAIR_A.signData(b);
			BlockResult br = s0.applyBlock(sb);
			// System.out.println("Transfer complete....");

			State newState = br.getState();
			assertEquals(AMT, newState.getBalance(ADDRESS_NIKI));
			// System.out.println("Niki has "+newState.getBalance(ADDRESS_NIKI).getValue());

		}

	}

	@Test
	public void testDeploys() throws BadSignatureException {
		State s = TestState.STATE;
		ATransaction t1 = Invoke.create(InitTest.HERO,1,Reader.read("(def my-lib-address (deploy '(defn foo [x] x)))"));
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		Block b1 = Block.of(s.getTimestamp().longValue(), kp.signData(t1));
		SignedData<Block> sb=KEYPAIR_PEER.signData(b1);
		BlockResult br=s.applyBlock(sb);
		assertFalse(br.isError(0),br.getResult(0).toString());

		s = br.getState();

	}
	
	@Test
	public void testBadBlockMissingPeer() throws BadSignatureException {
		State s = TestState.STATE;
		ATransaction t1 = Invoke.create(InitTest.HERO,1,Reader.read(":should-fail"));
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		Block b1 = Block.of(s.getTimestamp().longValue(), kp.signData(t1));
		SignedData<Block> sb=KEYPAIR_ROBB.signData(b1); // not a Peer!
		
		BlockResult br=s.applyBlock(sb);
		assertEquals(ErrorCodes.PEER,br.getResult(0).getErrorCode());
		assertEquals(Strings.MISSING_PEER,br.getResult(0).getValue());
		
		// Should be no state transition with a bad block
		assertEquals(s,br.getState());
	}
	
	@Test
	public void testBadBlockInsufficientStake() throws BadSignatureException {
		State s = TestState.STATE;
		AKeyPair pkp=KEYPAIR_ROBB;
		AccountKey peerKey=pkp.getAccountKey();
		s=s.withPeer(peerKey, PeerStatus.create(ADDRESS_A, 0));
		ATransaction t1 = Invoke.create(InitTest.HERO,1,Reader.read(":should-fail"));
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		Block b1 = Block.of(s.getTimestamp().longValue(), kp.signData(t1));
		SignedData<Block> sb=pkp.signData(b1); // not a Peer!
		
		BlockResult br=s.applyBlock(sb);
		assertEquals(ErrorCodes.PEER,br.getResult(0).getErrorCode());
		assertEquals(Strings.INSUFFICIENT_STAKE,br.getResult(0).getValue());
		
		// Should be no state transition with a bad block
		// TODO: slash Peer?
		assertEquals(s,br.getState());
	}


	@Test public void testManyDeploysMemoryRegression() throws BadSignatureException {
		State s=TestState.STATE;
		long lastSize=s.getMemorySize();
		assertTrue(lastSize>0);

		for (int i=1; i<=100; i++) { // i is sequence number
			ATransaction trans=Invoke.create(InitTest.HERO, i, Reader.read("(def storage-example\r\n"
					+ "  (deploy '(do (def stored-data nil)\r\n"
					+ "                     (defn get ^{:callable true} [] stored-data)\r\n"
					+ "                     (defn set ^{:callable true} [x] (def stored-data x)))))\r\n"));
			AKeyPair kp = InitTest.HERO_KEYPAIR;
			Block b=Block.of(s.getTimestamp().longValue(),kp.signData(trans));
			SignedData<Block> sb=KEYPAIR_PEER.signData(b);
			BlockResult br=s.applyBlock(sb);
			Result r=br.getResult(0);
			assertFalse(r.isError(),r.toString());
			assertTrue(r.getValue() instanceof Address);
			State newState=br.getState();

			long size=newState.getMemorySize();
			if (size<=lastSize) {
				fail("[i="+i+"] Original size: "+lastSize+" -> new size: "+size);
			}
			lastSize=size;
			s=newState;
		}
	}

	@Test
	public void testMemoryAccounting() throws BadSignatureException {
		State s = TestState.STATE;
		AKeyPair kp = InitTest.HERO_KEYPAIR;

		long initialMem=s.getMemorySize();

		ATransaction t1 = Invoke.create(InitTest.HERO,1,Reader.read("(def a 1)"));
		Block b1 = Block.of(s.getTimestamp().longValue(), kp.signData(t1));
		SignedData<Block> sb1=KEYPAIR_PEER.signData(b1);
		BlockResult br=s.applyBlock(sb1);

		// should not be an error
		assertNull(br.getErrorCode(0),br.getResult(0).toString());

		s = br.getState();

		// should have increased memory size for account
		long newMem=s.getMemorySize();
		assertTrue(initialMem<newMem);
		
		// Test for memory pool growth over time
		long memPool=s.getGlobalMemoryPool().longValue();
		long newTS=s.getTimestamp().longValue()+Constants.MEMORY_POOL_GROWTH_INTERVAL;
		Block b2 = Block.of(newTS);
		SignedData<Block> sb2=KEYPAIR_PEER.signData(b2);
		BlockResult br2=s.applyBlock(sb2);
		State s2=br2.getState();
		assertEquals(memPool+Constants.MEMORY_POOL_GROWTH,s2.getGlobalMemoryPool().longValue());
	}
	
	@Test
	public void testScheduleOps() throws BadSignatureException {
		State s = TestState.STATE;
		Address TARGET = InitTest.VILLAIN;
		String taddr=TARGET.toString();

		long INITIAL_TS = s.getTimestamp().longValue();
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		long BAL2 = s.getBalance(TARGET);

		ATransaction t1 = Invoke.create(InitTest.HERO,1,
				Reader.read("(transfer "+taddr+" 10000000)"));
		Block b1 = Block.of(s.getTimestamp().longValue() + 100, kp.signData(t1));
		SignedData<Block> sb1=KEYPAIR_PEER.signData(b1);
		s = s.applyBlock(sb1).getState();
		assertEquals(BAL2 + 10000000, s.getBalance(TARGET));
		assertCVMEquals(INITIAL_TS + 100, s.getTimestamp());

		// schedule 200ms later for 1s time
		ATransaction t2 = Invoke.create(InitTest.HERO,2, Reader.read(
				"(schedule (+ *timestamp* 1000) (transfer "+taddr+" 10000000))"));
		Block b2 = Block.of(s.getTimestamp().longValue() + 200, kp.signData(t2));
		SignedData<Block> sb2=KEYPAIR_PEER.signData(b2);
		BlockResult br2 = s.applyBlock(sb2);
		assertNull(br2.getErrorCode(0),br2.getResult(0).toString());
		s = br2.getState();
		Index<ABlob, AVector<ACell>> sched2 = s.getSchedule();
		assertEquals(1L, sched2.count());
		// no change to target balance yet
		assertEquals(BAL2 + 10000000, s.getBalance(TARGET));

		// advance 999ms
		ATransaction t3 = Invoke.create(InitTest.HERO,3, Reader.read("1"));
		Block b3 = Block.of(s.getTimestamp().longValue() + 999, kp.signData(t3));
		SignedData<Block> sb3=KEYPAIR_PEER.signData(b3);
		BlockResult br3 = s.applyBlock(sb3);
		assertNull(br3.getErrorCode(0));
		s = br3.getState();
		// no change to target balance yet
		assertEquals(BAL2 + 10000000, s.getBalance(TARGET));

		// advance 1ms to trigger scheduled transfer
		ATransaction t4 = Invoke.create(InitTest.HERO,4, Reader.read("1"));
		Block b4 = Block.of(s.getTimestamp().longValue() + 1, kp.signData(t4));
		SignedData<Block> sb4=KEYPAIR_PEER.signData(b4);
		BlockResult br4 = s.applyBlock(sb4);
		assertNull(br4.getErrorCode(0));
		s = br4.getState();
		// no change to target balance yet
		assertEquals(BAL2 + 20000000, s.getBalance(TARGET));

	}

}
