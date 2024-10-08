package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.AString;
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
		int size=status.size();
		int type = r.nextInt(5);
		switch (type) {
		case 0:
			return Samples.FOO;
		case 1:
			return Samples.BAR;
		case 2: 
			AString name=Gen.STRING.generate(r, status);
			if ((name.count()>0)&&(name.count()<=Keyword.MAX_CHARS)) {
				return Keyword.create(name);
			}
			// fallthorugh!
		default: {
				return Keyword.create("key" + r.nextLong(0, size));
			}
		}
	}
}
