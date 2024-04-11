package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
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
public class VectorGen extends Generator<AVector> {
	public VectorGen() {
		super(AVector.class);
	}

	@Override
	public AVector generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt(15);
		switch (type) {
		case 0: {
			ACell o = gen().make(PrimitiveGen.class).generate(r, status);
			return Vectors.of(o);
		}
		case 1: {
			ACell o1 = gen().make(PrimitiveGen.class).generate(r, status);
			ACell o2 = Gen.STRING.generate(r, status);
			return Vectors.of(o1, o2);
		}
		case 2: {
			ACell o1 = gen().make(ValueGen.class).generate(r, status);
			ACell o2 = gen().make(StringGen.class).generate(r, status);
			ACell o3 = gen().make(FormGen.class).generate(r, status);
			return Vectors.of(o1, o2, o3);
		}

		case 3:
			return Samples.INT_VECTOR_10;
		case 4:
			return Samples.INT_VECTOR_300;
		case 5:
			return Samples.INT_VECTOR_16;
		case 6:
			return Samples.INT_VECTOR_256;
		case 7:
			return Vectors.empty();
		case 8: {
			ACell o1 = gen().make(ValueGen.class).generate(r, status);
			ACell o2 = gen().make(ValueGen.class).generate(r, status);
			return MapEntry.create(o1, o2);
		}
		default: {
			int n = (int) (1 + (Math.sqrt(status.size())));
			ACell[] obs = new ACell[n];
			ValueGen g = gen().make(ValueGen.class);
			for (int i = 0; i < n; i++) {
				obs[i] = g.generate(r, status);
			}
			return Vectors.create(obs);
		}
		}
	}
}
