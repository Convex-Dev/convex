package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Generator for primitive data values
 */
public class PrimitiveGen extends Generator<ACell> {
	public final static PrimitiveGen INSTANCE = new PrimitiveGen();
	private static final int ONE_KB = 1024;
	private static final int ONE_MB = ONE_KB * 1024;

	// public final Generator<Byte> BYTE = gen().type(byte.class);

	public PrimitiveGen() {
		super(ACell.class);
	}

	@Override
	public ACell generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(7);
		switch (type) {
		case 0:
			return null;
		case 1:
			return CVMByte.create(r.nextLong());
		case 2:
			return CVMChar.create(r.nextLong()&0x1fffff); // 21-bit unicode space
		case 3:
			return CVMLong.create(r.nextLong());
		case 4:
			return CVMDouble.create(r.nextDouble());
		case 5:
			return CVMBool.create(r.nextBoolean());
		case 6:
			return Blob.create(r.nextBytes(getByteSize(r)));
		default:
			throw new Error("Unexpected type: " + type);
		}
	}

	private static int getByteSize(SourceOfRandomness r) {
		int rnd = r.nextInt(1, 100);

		// 1% change of getting number 0
		if (rnd == 1) {
			return 0;
		}

		// 1% change of getting number 1
		if (rnd == 2) {
			return 1;
		}

		// 15% chance of getting a "larger" size
		if (rnd >= 3 && rnd <= 17) {
			return r.nextInt(ONE_KB * 4, ONE_KB * 100);
		}

		// 5% chance of getting a "huge" size
		if (rnd >= 18 && rnd <= 22) {
			return r.nextInt(ONE_KB * 100, ONE_MB);
		}

		// 78% - normalish size
		return r.nextInt(2, ONE_KB * 4);
	}

}
