package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Constants;
import convex.core.data.ARecord;
import convex.core.init.InitTest;
import convex.core.lang.TestState;

/**
 * Generator for binary Blobs
 *
 */
public class RecordGen extends Generator<ARecord> {
	public RecordGen() {
		super(ARecord.class);
	}

	@Override
	public ARecord generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt();
		switch (type % 8) {
		case 0:
			return Belief.createSingleOrder(InitTest.HERO_KEYPAIR);
		case 1:
			return TestState.STATE;
		default:
			return Block.of(Constants.INITIAL_TIMESTAMP);
		}
	}
}
