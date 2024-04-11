package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.test.Samples;

/**
 * Generator for sets of values
 */
@SuppressWarnings("rawtypes")
public class SetGen extends Generator<ASet> {
	public SetGen() {
		super(ASet.class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ASet generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt();
		switch (type % 10) {
		case 0:
			return Sets.empty();
		case 1: {
			Object o1 = gen().make(ValueGen.class).generate(r, status);
			return Sets.of(o1);
		}
		case 2:
			return Samples.LONG_SET_5;
		case 3:
			return Samples.LONG_SET_10;
		case 4:
			return Samples.LONG_SET_100;
		case 5:
			return Sets.of(CVMLong.create(r.nextLong(-status.size(),status.size())));
		default: {
			AVector<ACell> o1 = gen().make(VectorGen.class).generate(r, status);
			return Sets.create(o1);
		}
		}
	}
}
