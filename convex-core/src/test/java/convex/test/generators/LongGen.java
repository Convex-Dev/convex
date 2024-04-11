package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMLong;

/**
 * Generator for arbitrary numeric values
 *
 */
public class LongGen extends Generator<CVMLong> {
	public LongGen() {
		super(CVMLong.class);
	}

	@Override
	public CVMLong generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		switch (r.nextInt(8) ) {
		case 0: 
			return CVMLong.ZERO;
		case 1: 
			return CVMLong.ONE;
		case 2: 
			return CVMLong.create(Long.MAX_VALUE);
		case 3: 
			return CVMLong.create(Long.MIN_VALUE);
		case 4: 
			return CVMLong.create(1<<r.nextInt(Math.min(size, 64)));
		default:
			return CVMLong.create(r.nextLong(-size, size));

		}
	}
}
