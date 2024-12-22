package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.AByteFlag;
import convex.core.data.prim.ByteFlag;

/**
 * Generator for CAD3 byte flags
 *
 */
public class ByteFlagGen extends AGenerator<AByteFlag> {
	public ByteFlagGen() {
		super(AByteFlag.class);
	}

	@Override
	public AByteFlag generate(SourceOfRandomness r, GenerationStatus status) {
		switch (r.nextInt(2)) {
		case 0:
			return Gen.BOOLEAN.generate(r,status);
		default:
			int n=r.nextInt(16);
			return ByteFlag.create(n);

		}
	}
}
