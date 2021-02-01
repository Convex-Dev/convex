package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

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
			return CVMLong.create(r.nextLong());
		case 4:
			return CVMDouble.create(r.nextDouble());
		case 5:
			return CVMBool.create(r.nextBoolean());
		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
