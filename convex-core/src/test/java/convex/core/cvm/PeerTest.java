package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import convex.core.crypto.AKeyPair;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.ObjectsTest;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.CPoSConstants;

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
		AMap<Keyword, ACell> data2=FileUtils.loadCAD3(temp);
		
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
