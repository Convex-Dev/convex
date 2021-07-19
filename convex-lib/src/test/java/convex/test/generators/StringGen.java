package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.test.Samples;

public class StringGen extends Generator<AString> {
	public StringGen() {
		super(AString.class);
	}

	@Override
	public AString generate(SourceOfRandomness r, GenerationStatus status) {

	 	int type=r.nextInt();
    	switch (type%12) {
    		case 0: return Strings.empty();
    		case 1: return Samples.MAX_EMBEDDED_STRING;
    		case 2: return Samples.NON_EMBEDDED_STRING;
    		case 3: return Samples.MAX_SHORT_STRING;
       		case 4: return Samples.MIN_TREE_STRING;
       	    		
    		default: {
    			return Strings.create(gen().type(String.class).generate(r, status));
    		}
    	}
	}
}
