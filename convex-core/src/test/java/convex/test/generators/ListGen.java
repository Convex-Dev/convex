package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.AList;
import convex.core.data.List;

/**
 * Generator for vectors of arbitrary values
 *
 */
@SuppressWarnings("rawtypes")
public class ListGen extends Generator<AList> {
	public ListGen() {
		super(AList.class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AList generate(SourceOfRandomness r, GenerationStatus status) {

		return List.reverse(Gen.VECTOR.generate(r, status));
	}
}
