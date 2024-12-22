package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generator for arbitrary Addresses
 */
public abstract class AGenerator<T> extends Generator<T> {
	
	protected AGenerator(Class<T> type) {
		super(type);
	}

	@Override
	public abstract T generate(SourceOfRandomness r, GenerationStatus status);
}
