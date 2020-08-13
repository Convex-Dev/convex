package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generator for arbitrary numeric values
 *
 */
public class NumericGen extends Generator<Object> {
	public NumericGen() {
		super(Object.class);
	}

	@Override
	public Object generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(6);
		switch (type) {
		case 0:
			return gen().type(byte.class).generate(r, status);
		case 1:
			return gen().type(short.class).generate(r, status);
		case 2:
			return gen().type(int.class).generate(r, status);
		case 3:
			return gen().type(long.class).generate(r, status);
		case 4:
			return gen().type(float.class).generate(r, status);
		case 5:
			return gen().type(double.class).generate(r, status);
//			case 6:
//				return gen().type(BigInteger.class).generate(r, status);
//			case 7:
//				return gen().type(BigDecimal.class).generate(r, status);
		}
		throw new Error("Unexpected type: " + type);
	}
}
