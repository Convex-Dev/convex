package convex.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.Keyword;
import convex.test.Samples;

/**
 * Generator for Keyword objects
 * 
 */
public class KeywordGen extends Generator<Keyword> {
	public KeywordGen() {
		super(Keyword.class);
	}

	@Override
	public Keyword generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt(5);
		switch (type) {
		case 0:
			return Samples.FOO;
		case 1:
			return Samples.BAR;
		default: {
			return Keyword.create("key" + r.nextLong(0, status.size()));
		}
		}
	}
}
