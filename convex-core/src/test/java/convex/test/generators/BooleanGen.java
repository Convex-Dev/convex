package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMBool;



/**
 * Generator for booleans
 */
public class BooleanGen extends AGenerator<CVMBool> {
	public BooleanGen() {
		super(CVMBool.class);
	}

	@Override
	public CVMBool generate(SourceOfRandomness r, GenerationStatus status) {

		switch (r.nextInt(2)) {
		case 0:
			return CVMBool.TRUE;
		default:
			return CVMBool.FALSE;

		}
	}
}
