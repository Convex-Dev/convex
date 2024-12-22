package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ABlob;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Generator for arbitrary numeric values
 *
 */
public class IntegerGen extends AGenerator<AInteger> {
	public IntegerGen() {
		super(AInteger.class);
	}

	@Override
	public AInteger generate(SourceOfRandomness r, GenerationStatus status) {
		// int size=status.size();
		switch (r.nextInt(6) ) {
		case 0: 
			return CVMLong.ZERO;
		case 1: 
			return (AInteger)RT.multiply(Gen.LONG.generate(r, status),Gen.LONG.generate(r, status));
		case 3: 
			ABlob b= Gen.BLOB.generate(r, status);
			CVMBigInteger bi=CVMBigInteger.create(b);
			if (bi!=null) return bi;
			// fallthorugh if null
		default:
			return Gen.LONG.generate(r, status);
		}
	}
}
