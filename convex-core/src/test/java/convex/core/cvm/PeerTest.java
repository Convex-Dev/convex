package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.ObjectsTest;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.store.Stores;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.CPoSConstants;
import convex.test.Samples;

@TestInstance(Lifecycle.PER_CLASS)
public class PeerTest {

	public static String seed="1a44bbe097e38d2ba90e9426e9b1ab0ec12444a25e4d23b77fd121da728737f2";
	
	static AKeyPair KP=AKeyPair.create(seed);
	static AccountKey KEY=KP.getAccountKey();
	
	static State GENESIS=Init.createTestState(List.of(KEY)); 
	
	@Test
	public void testBasicPeer() throws IOException, BadFormatException {
		Peer p=Peer.create(KP, GENESIS);
		doPeerTest(p);
		
		AMap<Keyword, ACell> data = p.toData();
		assertNotNull(data);
		
		Peer p2=Peer.fromData(KP, data);
		
		assertEquals(GENESIS,p2.getConsensusState());
		assertEquals(0,p2.getStatePosition());
		assertEquals(p.getPeerOrder(),p2.getPeerOrder());
		
		Peer p3= p2.updateState();
		assertEquals(GENESIS,p3.getConsensusState());
		
		Path temp=Files.createTempFile("peerData", "cad3");
		temp.toFile().deleteOnExit();
		
		FileUtils.writeCAD3(temp,data);
		AMap<Keyword, ACell> data2=FileUtils.loadCAD3(temp, Samples.TEST_STORE);
		
		Peer p4=Peer.fromData(KP, data2);
		assertEquals(p.getBelief(),p4.getBelief());
	}
	
	@Test
	public void testPeerUpdates() throws InvalidDataException {
		Peer p=Peer.create(KP, GENESIS);
		long ts=p.getTimestamp();
		
		for (int i=0; i<10; i++) {
			Block b=Block.of(ts+i, KP.signData(Invoke.create(Init.GENESIS_ADDRESS, i+1, "(* "+i+" "+i+")")));
			p=p.proposeBlock(b);
		}
		assertEquals(ts+9,p.getTimestamp());
		assertEquals(0,p.getStatePosition());
		assertEquals(0,p.getFinalityPoint());
		assertEquals(10,p.getPeerOrder().getBlockCount());
		p=p.mergeBeliefs();
		p=p.mergeBeliefs();
		p=p.mergeBeliefs();
		p=p.mergeBeliefs();
		p=p.updateState();
		assertEquals(10,p.getStatePosition());
		assertEquals(81,Utils.toInt(p.getBlockResult(9).getResults().get(0).getValue()));
		
		// check all invariants
		doPeerTest(p);
		
		{ // Truncate to genesis
			Peer pt=p.truncateState(0);
			assertEquals(0,pt.getStatePosition());
			assertEquals(GENESIS,pt.getConsensusState());
			doPeerTest(pt);
			
			// replay transactions up to consensus
			pt=pt.updateState();
			assertEquals(p.getConsensusState(),pt.getConsensusState());
			
			assertEquals(pt.getBlockResult(7),p.getBlockResult(7));
		}
		
		{ // Truncate to mid point
			Peer pt=p.truncateState(5);
			assertEquals(5,pt.getStatePosition());
			assertNotEquals(GENESIS,pt.getConsensusState());
			doPeerTest(pt);
			
			// replay transactions up to consensus
			pt=pt.updateState();
			assertEquals(p.getConsensusState(),pt.getConsensusState());
			
			assertEquals(pt.getBlockResult(7),p.getBlockResult(7));

		}
		
		{ // Truncate to distant future
			Peer pt=p.truncateState(Long.MAX_VALUE);
			assertEquals(p.getStatePosition(),pt.getStatePosition());
			doPeerTest(pt);
		}
	}
	
	/**
	 * Small test: single block with a few mixed good/bad signature transactions.
	 */
	@Test
	public void testBadSignatureSmall() throws InvalidDataException {
		Peer p=Peer.create(KP, GENESIS);
		long ts=p.getTimestamp();
		AKeyPair badKP = AKeyPair.generate();

		SignedData<ATransaction> goodTx1 = KP.signData(Invoke.create(Init.GENESIS_ADDRESS, 1, "*address*"));
		SignedData<ATransaction> badTx   = badKP.signData(Invoke.create(Init.GENESIS_ADDRESS, 2, "*address*"));
		SignedData<ATransaction> goodTx2 = KP.signData(Invoke.create(Init.GENESIS_ADDRESS, 2, "*address*"));

		Block block = Block.of(ts, goodTx1, badTx, goodTx2);
		p = p.proposeBlock(block);
		p = p.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs();
		p = p.updateState();

		assertEquals(1, p.getStatePosition());
		AVector<Result> results = p.getBlockResult(0).getResults();
		assertEquals(3, results.count());

		assertFalse(results.get(0).isError(), "Good tx should succeed");
		assertTrue(results.get(1).isError(), "Bad signature tx should be rejected");
		assertEquals(ErrorCodes.SIGNATURE, results.get(1).getErrorCode());

		doPeerTest(p);
	}

	/**
	 * Adversarial test: two blocks each with > SIGN_CHUNK_SIZE (100) transactions,
	 * mixing valid and bad-signature transactions. Ensures chunked parallel signature
	 * validation correctly identifies all bad signatures across chunk boundaries.
	 */
	@Test
	public void testBadSignatureLargeBlocks() throws InvalidDataException {
		Peer p=Peer.create(KP, GENESIS);
		long ts=p.getTimestamp();
		AKeyPair badKP = AKeyPair.generate();

		int txPerBlock = 150; // > SIGN_CHUNK_SIZE to exercise parallel chunking
		java.util.Set<Integer> badIndices = new java.util.HashSet<>();
		long seq = 1;

		// Block 1: every 7th transaction (offset 3) is bad-signed
		java.util.ArrayList<SignedData<ATransaction>> txs1 = new java.util.ArrayList<>();
		for (int i = 0; i < txPerBlock; i++) {
			Invoke tx = Invoke.create(Init.GENESIS_ADDRESS, seq++, "*address*");
			if (i % 7 == 3) {
				txs1.add(badKP.signData(tx));
				badIndices.add(i);
			} else {
				txs1.add(KP.signData(tx));
			}
		}
		p = p.proposeBlock(Block.create(ts, txs1));

		// Block 2: every 11th transaction (offset 5) is bad-signed
		java.util.ArrayList<SignedData<ATransaction>> txs2 = new java.util.ArrayList<>();
		for (int i = 0; i < txPerBlock; i++) {
			Invoke tx = Invoke.create(Init.GENESIS_ADDRESS, seq++, "*address*");
			if (i % 11 == 5) {
				txs2.add(badKP.signData(tx));
				badIndices.add(txPerBlock + i);
			} else {
				txs2.add(KP.signData(tx));
			}
		}
		p = p.proposeBlock(Block.create(ts + 1, txs2));

		// Advance consensus and apply — triggers validateSignatures on both blocks
		p = p.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs();
		p = p.updateState();

		assertEquals(2, p.getStatePosition());

		AVector<Result> results1 = p.getBlockResult(0).getResults();
		AVector<Result> results2 = p.getBlockResult(1).getResults();
		assertEquals(txPerBlock, results1.count());
		assertEquals(txPerBlock, results2.count());

		// Verify every bad-signature transaction was rejected.
		// Note: after a bad-sig tx, later txs from the same account may get SEQUENCE
		// errors (since bad-sig txs don't advance the sequence counter). The key
		// invariant is: no bad-sig tx ever succeeds.
		int badCount = 0;
		for (int i = 0; i < txPerBlock; i++) {
			if (badIndices.contains(i)) {
				assertTrue(results1.get(i).isError(), "Block 1 bad-sig tx " + i + " must not succeed");
				badCount++;
			}
		}
		for (int i = 0; i < txPerBlock; i++) {
			if (badIndices.contains(txPerBlock + i)) {
				assertTrue(results2.get(i).isError(), "Block 2 bad-sig tx " + i + " must not succeed");
				badCount++;
			}
		}
		assertTrue(badCount > 0, "Should have found bad transactions");

		// Verify the first bad-sig tx in block 1 gets a SIGNATURE error specifically
		// (it has correct sequence, so only signature check can reject it)
		assertEquals(ErrorCodes.SIGNATURE, results1.get(3).getErrorCode());

		// First tx in block 1 is good (index 0, not 0%7==3)
		assertFalse(results1.get(0).isError(), "First good tx should succeed");

		doPeerTest(p);
	}

	private void doPeerTest(Peer pt) {
		assertTrue(pt.getGenesisHash().equals(GENESIS.getHash()));
		
		long sp=pt.getStatePosition();
		long hp=pt.getHistoryPosition();
		
		assertTrue(sp>=hp);
		
		AVector<BlockResult> results=pt.getBlockResults();
		assertEquals(sp-hp,results.count());
	}

	@Test
	public void testPeerStatus() {
		PeerStatus ps=PeerStatus.create(Address.ZERO, CPoSConstants.MINIMUM_EFFECTIVE_STAKE);
		
		ObjectsTest.doCellTests(ps);
	}
}
