package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMChar;

/**
 * Generator for primitive data values
 */
public class CharGen extends AGenerator<CVMChar> {
	public final static CharGen INSTANCE = new CharGen();

	// public final Generator<Byte> BYTE = gen().type(byte.class);

	public CharGen() {
		super(CVMChar.class);
	}

	@Override
	public CVMChar generate(SourceOfRandomness r, GenerationStatus status) {
		switch (r.nextInt(6)) {
		case 0:
			return CVMChar.ZERO;
		case 1:
			return CVMChar.create(r.nextLong(0,CVMChar.MAX_CODEPOINT)); // 21-bit unicode space
		default:
			return CVMChar.create(r.nextInt(32, 127));
		}
	}



}
