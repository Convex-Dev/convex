package convex.core.cpos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.RecordTest;
import convex.core.data.Refs;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.test.Samples;

public class OrderTest {
	AKeyPair KP=Samples.KEY_PAIR;

	@Test public void testEmptyOrder() {
		Order o=Order.create();
		assertEquals(0,o.getTimestamp());
		assertEquals(0,o.getBlockCount());
		
		// Consensus cells (1+4) + timestamp (1) + empty vector (1)+Top leevl
		assertEquals(4+CPoSConstants.CONSENSUS_LEVELS,Refs.totalRefCount(o));
		RecordTest.doRecordTests(o);
	}
	
	@Test public void testBigOrder() {
		Order o=Order.create();
		o=o.withTimestamp(1234);
		assertEquals(1234,o.getTimestamp());
		
		int NUM_BLOCKS=300;
		SignedData<Block> sb=KP.signData(Block.of(123));
		o=o.withBlocks(Vectors.repeat(sb, NUM_BLOCKS));
		assertEquals(NUM_BLOCKS,o.getBlockCount());
		assertEquals(NUM_BLOCKS,o.getConsensusPoint(0));
		assertEquals(0,o.getConsensusPoint(1));
		assertEquals(sb,o.getBlock(10));
		

		RecordTest.doRecordTests(o);
	}
}
