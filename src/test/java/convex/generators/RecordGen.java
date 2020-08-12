package convex.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Constants;
import convex.core.Init;
import convex.core.data.ARecord;

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
			return Belief.createSingleOrder(Init.HERO_KP);
		case 1:
			return Init.STATE;
		default:
			return Block.of(Constants.INITIAL_TIMESTAMP);
		}
	}
}
