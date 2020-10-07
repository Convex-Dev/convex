package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.AString;
import convex.core.data.Strings;

public class StringGen extends Generator<AString> {
	public StringGen() {
		super(AString.class);
	}

	@Override
	public AString generate(SourceOfRandomness r, GenerationStatus status) {

	 	int type=r.nextInt();
    	switch (type%6) {
    		case 0: return Strings.empty();

    		
    		default: {
    			return Strings.create(gen().type(String.class).generate(r, status));
    		}
    	}
	}
}
