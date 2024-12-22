package convex.test.generators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ABlob;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.test.Samples;

/**
 * Generator for arbitrary maps, including Indexes
 *
 */
@SuppressWarnings("rawtypes")
public class AnyMapGen extends AGenerator<AMap> {
	public AnyMapGen() {
		super(AMap.class);
	}

	@Override
	public AMap generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt();
		switch (type % 10) {
		case 0:
			return Maps.empty();
		case 1:
			return Samples.LONG_MAP_5;
		case 2:
			return Samples.LONG_MAP_10;
		case 3:
			return Samples.LONG_MAP_100;
		case 4: {
			Object o1 = gen().make(PrimitiveGen.class).generate(r, status);
			Object o2 = gen().make(StringGen.class).generate(r, status);
			return Maps.of(o1, o2);
		}
		case 5:
			return Index.EMPTY;
		case 6: {
			ABlob o1 = Cells.encode(gen().make(PrimitiveGen.class).generate(r, status));
			AString o2 = gen().make(StringGen.class).generate(r, status);
			return Index.create(o1, o2);
		}

		case 7: {
			AVector<?> vec = gen().make(VectorGen.class).generate(r, status);
			vec = (AVector<?>) vec.subList(0, vec.size() & (~1));
			Object[] os = vec.toArray();
			return Maps.of(os);
		}
		
		default:
			return Gen.HASHMAP.generate(r, status);
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<AMap> doShrink(SourceOfRandomness r, AMap m) {
		long n=m.count();
		if (n==0) return Collections.EMPTY_LIST;
		if (n==1) return Collections.singletonList(Maps.empty());
		ArrayList<AMap> al=new ArrayList<>();
		
		for (int i=0; i<n; i++) {
			al.add(m.dissoc(m.entryAt(i).getKey()));
		}
		
		Collections.sort(al,Cells.countComparator);
		
		return al;
	}
}
