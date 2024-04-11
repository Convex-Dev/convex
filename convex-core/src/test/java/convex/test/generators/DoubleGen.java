package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.prim.CVMDouble;

/**
 * Generator for arbitrary numeric values
 *
 */
public class DoubleGen extends Generator<CVMDouble> {
	public DoubleGen() {
		super(CVMDouble.class);
	}

	@Override
	public CVMDouble generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		switch (r.nextInt(8) ) {
		case 0: 
			return CVMDouble.ZERO;
		case 1: return CVMDouble.ONE;
		case 2:
			return CVMDouble.create((r.nextDouble()-0.5)*0.1*r.nextLong(-size,3*size));
		case 3:
			return CVMDouble.create(((double)r.nextInt(-size, size))/r.nextInt(-size, size));	
		case 4:
			return Gen.LONG.generate(r,status).toDouble();	
		default:
			return CVMDouble.create(Math.pow(2.0*r.nextDouble(), r.nextLong(-size,size)));

		}
	}
}
