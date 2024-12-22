package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.test.Samples;

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
		int type = r.nextInt(12);
		switch (type) {
		case 0:
			return CVMLong.forByte((byte)r.nextLong());
		case 1:
			return CVMDouble.create((r.nextDouble()-0.5)*0.1*r.nextLong(-status.size(),3*status.size()));
		case 2:
			ANumeric num1=generate(r,status);
			ANumeric result= RT.multiply(num1,generate(r,status));
			if (result!=null) return result;
			return num1;
		case 3:
			return CVMDouble.NaN;
		case 4:
			return Gen.DOUBLE.generate(r,status);
		case 5:
			return Samples.MIN_BIGINT;
		case 6:
			return Samples.MAX_BIGINT;
		case 7:
			return CVMBigInteger.MIN_POSITIVE;
		case 8:
			return CVMBigInteger.MIN_NEGATIVE;
		case 9:
			return CVMDouble.create(Double.longBitsToDouble(r.nextLong()));
			
			
		default:
			return CVMLong.create(r.nextLong(-status.size(),status.size()));

		}
	}
}
