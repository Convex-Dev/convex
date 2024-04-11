package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.test.Samples;

/**
 * Generator for arbitrary maps
 *
 */
@SuppressWarnings("rawtypes")
public class HashMapGen extends Generator<AHashMap> {
    public HashMapGen() {
        super(AHashMap.class);
    }

    @SuppressWarnings("unchecked")
	@Override public AHashMap generate(
        SourceOfRandomness r,
        GenerationStatus status) {
    	
    	int type=r.nextInt();
    	switch (type%6) {
    		case 0: return Maps.empty();
    		case 1: return Samples.LONG_MAP_5;
    		case 2: return Samples.LONG_MAP_10;
    		case 3: return Samples.LONG_MAP_100;
    		case 4: {
    			ACell o1=gen().make(PrimitiveGen.class).generate(r, status);
    			ACell o2=gen().make(StringGen.class).generate(r, status);
    			return Maps.create(o1,o2);
    		}
    		
    		default: {
    			AVector<?> vec=gen().make(VectorGen.class).generate(r, status);
    			vec=vec.slice(0, vec.size()&(~1));
    			Object[] os=vec.toArray();
    			return Maps.of(os);
    		}
    	}
    }
}
