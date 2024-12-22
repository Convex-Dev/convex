package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMDouble;

/**
 * Generator for arbitrary numeric values
 *
 */
public class DoubleGen extends AGenerator<CVMDouble> {
	private static final CVMDouble[] ODDITIES = new CVMDouble[] {CVMDouble.NaN, CVMDouble.NEGATIVE_INFINITY, CVMDouble.NEGATIVE_ZERO, CVMDouble.POSITIVE_INFINITY};

	public DoubleGen() {
		super(CVMDouble.class);
	}

	@Override
	public CVMDouble generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		switch (r.nextInt(10) ) {
		case 0: 
			return CVMDouble.ZERO;
		case 1: 
			return CVMDouble.ONE;
		case 2:
			return CVMDouble.MINUS_ONE;
		case 3:
			return CVMDouble.create((r.nextDouble()-0.5)*0.1*r.nextLong(-size,3*size));
		case 4:
			return CVMDouble.create(((double)r.nextInt(-size, size))/r.nextInt(-size, size));	
		case 5:
			return Gen.LONG.generate(r,status).toDouble();	
		case 6:
			return ODDITIES[r.nextInt(ODDITIES.length)];	

		default:
			return CVMDouble.create(Math.pow(2.0*r.nextDouble(), r.nextLong(-size,size)));

		}
	}
}
