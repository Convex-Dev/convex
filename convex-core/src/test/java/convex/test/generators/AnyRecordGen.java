package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.Constants;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cvm.Receipt;
import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.Vectors;
import convex.core.init.InitTest;
import convex.core.lang.TestState;

/**
 * Generator for records, might not be CVM Values
 *
 */
public class AnyRecordGen extends Generator<ARecord> {
	public AnyRecordGen() {
		super(ARecord.class);
	}

	@Override
	public ARecord generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt();
		switch (type % 8) {
		case 0: {
			ACell v= gen().make(ValueGen.class).generate(r, status);
			return Receipt.create(v);
			}
		case 1: {
			ACell v= gen().make(ValueGen.class).generate(r, status);
			return Receipt.create(false, v, Vectors.empty());
		}
		case 2:
			return Belief.createSingleOrder(InitTest.HERO_KEYPAIR);
		case 3:
			return TestState.STATE;
		default:
			return Block.of(Constants.INITIAL_TIMESTAMP);
		}
	}
}
