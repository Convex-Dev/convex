package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMByte;

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
		int type = r.nextInt(6);
		switch (type) {
		case 0:
			return null;
		case 1:
			return CVMByte.create(r.nextLong());
		case 2:
			return gen().type(char.class).generate(r, status);
		case 3:
			return gen().type(long.class).generate(r, status);
		case 4:
			return gen().type(double.class).generate(r, status);
		case 5:
			return gen().type(boolean.class).generate(r, status);
		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
