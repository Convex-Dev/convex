package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.APrimitive;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Generator for arbitrary numeric values
 *
 */
public class NumericGen extends Generator<APrimitive> {
	public NumericGen() {
		super(APrimitive.class);
	}

	@Override
	public APrimitive generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(3);
		switch (type) {
		case 0:
			return CVMLong.forByte((byte)r.nextLong());
		case 1:
			return CVMLong.create(r.nextLong());
		case 2:
			return CVMDouble.create(r.nextDouble());
// TODO: bigger numerics?
//			case 6:
//				return gen().type(BigInteger.class).generate(r, status);
//			case 7:
//				return gen().type(BigDecimal.class).generate(r, status);
		}
		throw new Error("Unexpected type: " + type);
	}
}
