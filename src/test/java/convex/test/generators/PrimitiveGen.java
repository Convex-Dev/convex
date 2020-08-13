package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generator for primitive data values
 */
public class PrimitiveGen extends Generator<Object> {
	public final static PrimitiveGen INSTANCE = new PrimitiveGen();

	// public final Generator<Byte> BYTE = gen().type(byte.class);

	public PrimitiveGen() {
		super(Object.class);
	}

	@Override
	public Object generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(9);
		switch (type) {
		case 0:
			return null;
		case 1:
			return gen().type(byte.class).generate(r, status);
		case 2:
			return gen().type(char.class).generate(r, status);
		case 3:
			return gen().type(short.class).generate(r, status);
		case 4:
			return gen().type(int.class).generate(r, status);
		case 5:
			return gen().type(long.class).generate(r, status);
		case 6:
			return gen().type(float.class).generate(r, status);
		case 7:
			return gen().type(double.class).generate(r, status);
		case 8:
			return gen().type(boolean.class).generate(r, status);
		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
