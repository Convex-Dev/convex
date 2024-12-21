package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.APrimitive;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMLong;

/**
 * Generator for primitive data values
 */
public class PrimitiveGen extends Generator<APrimitive> {
	public final static PrimitiveGen INSTANCE = new PrimitiveGen();

	// public final Generator<Byte> BYTE = gen().type(byte.class);

	public PrimitiveGen() {
		super(APrimitive.class);
	}

	@Override
	public APrimitive generate(SourceOfRandomness r, GenerationStatus status) {
		switch (r.nextInt(10)) {
		case 0:
			return null;
		case 1:
			return CVMLong.forByte((byte)r.nextLong());
		case 2:
			return CVMChar.create(r.nextLong()&0x1fffff); // 21-bit unicode space
		case 3:
			return Gen.INTEGER.generate(r, status);
		case 4:
			return Gen.DOUBLE.generate(r, status);
		case 5:
			return Gen.INTEGER.generate(r, status);
		case 6:
			return CVMBool.create(r.nextBoolean());
		case 7:
			return Gen.BYTE_FLAG.generate(r,status);

		default:
			return Gen.LONG.generate(r, status);
		}
	}



}
