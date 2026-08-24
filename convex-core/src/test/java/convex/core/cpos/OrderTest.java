package convex.core.cpos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.RecordTest;
import convex.core.data.Refs;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
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

	@Test public void testVariableConsensusLevels() throws Exception {
		SignedData<Block> sb=KP.signData(Block.of(123));

		Order shortOrder=Order.create(Vectors.of(
				CVMLong.ZERO,
				Vectors.createLongs(new long[] {1,1}),
				Vectors.of(sb)));
		shortOrder.validate();
		assertEquals(1,shortOrder.getConsensusPoint(0));
		assertEquals(1,shortOrder.getConsensusPoint(1));
		assertEquals(0,shortOrder.getConsensusPoint(2));
		assertEquals(0,shortOrder.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));
		assertEquals(2,shortOrder.getConsensusPoints().length);
		assertEquals(CPoSConstants.CONSENSUS_LEVELS,
				shortOrder.getConsensusPoints(CPoSConstants.CONSENSUS_LEVELS).length);
		assertArrayEquals(new long[] {1,1,0,0},
				shortOrder.getConsensusPoints(CPoSConstants.CONSENSUS_LEVELS));

		Order extraOrder=Order.create(Vectors.of(
				CVMLong.ZERO,
				Vectors.createLongs(new long[] {1,1,1,1,0,0}),
				Vectors.of(sb)));
		extraOrder.validate();
		Order standardOrder=Order.create(1,1,sb);
		standardOrder=standardOrder.withConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY,1);
		assertEquals(6,extraOrder.getConsensusPoints().length);
		assertEquals(standardOrder.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY),
				extraOrder.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));
		assertEquals(standardOrder.getBlocks(),extraOrder.getBlocks());
		assertTrue(standardOrder.consensusEquals(extraOrder));
		assertArrayEquals(new long[] {1,1,1,1},
				extraOrder.getConsensusPoints(CPoSConstants.CONSENSUS_LEVELS));
		assertTrue(shortOrder.consensusEquals(standardOrder,2));

		Order noLevels=Order.create(Vectors.of(CVMLong.ZERO,Vectors.empty(),Vectors.of(sb)));
		noLevels.validate();
		assertEquals(1,noLevels.getConsensusPoint(0));
		assertEquals(0,noLevels.getProposalPoint());
		assertEquals(0,noLevels.getConsensusPoint());
	}

	@Test public void testConsensusLevelValidation() {
		SignedData<Block> sb=KP.signData(Block.of(123));

		Order wrongBlockCount=Order.create(Vectors.of(
				CVMLong.ZERO,Vectors.createLongs(new long[] {0}),Vectors.of(sb)));
		assertEquals(1,wrongBlockCount.getConsensusPoint(0));
		assertThrows(InvalidDataException.class,wrongBlockCount::validate);

		Order negative=Order.create(Vectors.of(
				CVMLong.ZERO,Vectors.createLongs(new long[] {1,-1}),Vectors.of(sb)));
		assertEquals(0,negative.getConsensusPoint(1));
		assertThrows(InvalidDataException.class,negative::validate);

		Order outOfSequence=Order.create(Vectors.of(
				CVMLong.ZERO,Vectors.createLongs(new long[] {1,0,1}),Vectors.of(sb)));
		assertEquals(0,outOfSequence.getConsensusPoint(2));
		assertThrows(InvalidDataException.class,outOfSequence::validate);

		assertThrows(IllegalArgumentException.class,
				()->Order.create().withConsensusPoints(new long[] {0,0,1}));
		assertThrows(IllegalArgumentException.class,()->Order.create().getConsensusPoint(-1));
	}
}
