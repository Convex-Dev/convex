package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Generator for arbitrary numeric values
 *
 */
public class NumericGen extends Generator<ANumeric> {
	public NumericGen() {
		super(ANumeric.class);
	}

	@Override
	public ANumeric generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(8);
		switch (type) {
		case 0:
			return CVMLong.forByte((byte)r.nextLong());
		case 1:
			return CVMDouble.create((r.nextDouble()-0.5)*0.1*r.nextLong(-status.size(),3*status.size()));
		case 2:
			return RT.multiply(generate(r,status),generate(r,status));
// TODO: bigger numerics?
//			case 6:
//				return gen().type(BigInteger.class).generate(r, status);
//			case 7:
//				return gen().type(BigDecimal.class).generate(r, status);
		case 3:
			return CVMDouble.NaN;
		case 4:
			return Gen.DOUBLE.generate(r,status);
			
			
		default:
			return CVMLong.create(r.nextLong(-status.size(),status.size()));

		}
	}
}
