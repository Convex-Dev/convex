package convex.test.generators;

import com.pholser.junit.quickcheck.generator.ComponentizedGenerator;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Vectors;
import convex.test.Samples;

/**
 * Generator for vectors of arbitrary values
 *
 */
@SuppressWarnings("rawtypes")
public class VectorGen extends ComponentizedGenerator<AVector> {
	public VectorGen() {
		super(AVector.class);
	}

	@Override
	public AVector generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt(15);
		switch (type) {
		case 0:
			return Vectors.empty();
		case 1: {
			ACell o = Gen.PRIMITIVE.generate(r, status);
			return Vectors.of(o);
		}
		case 2: {
			ACell o1 = Gen.PRIMITIVE.generate(r, status);
			ACell o2 = Gen.STRING.generate(r, status);
			return Vectors.of(o1, o2);
		}
		case 3: {
			ACell o1 = Gen.VALUE.generate(r, status);
			ACell o2 = Gen.STRING.generate(r, status);
			ACell o3 = Gen.FORM.generate(r, status);
			return Vectors.of(o1, o2, o3);
		}
		case 4:
			return Samples.INT_VECTOR_10;
		case 5:
			return Samples.INT_VECTOR_300;
		case 6:
			return Samples.INT_VECTOR_16;
		case 7:
			return Samples.INT_VECTOR_256;
		case 8: {
			// A MapEntry
			ACell o1 = Gen.VALUE.generate(r, status);
			ACell o2 = Gen.VALUE.generate(r, status);
			return MapEntry.create(o1, o2);
		}
		case 9: {
			// Canonical vector version
			return generate(r,status).toVector();
		}
		case 10: {
			// Slice of some other vector
			AVector v=generate(r,status);
			long n=v.count();
			return v.slice(n/3, (2*n/3));
		}
		default: {
			int n = (int) (1 + (Math.sqrt(status.size())));
			ACell[] obs = new ACell[n];
			ValueGen g = Gen.VALUE;
			for (int i = 0; i < n; i++) {
				obs[i] = g.generate(r, status);
			}
			return Vectors.create(obs);
		}
		}
	}
	
    @Override public int numberOfNeededComponents() {
        return 1;
    }
}
