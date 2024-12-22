package convex.test.generators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Cells;
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
			ACell o1 = Gen.PRIMITIVE.generate(r, status);
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
			AVector<ACell> o1 = Gen.VECTOR.generate(r, status);
			return Sets.create(o1);
		}
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<ASet> doShrink(SourceOfRandomness r, ASet v) {
		long n=v.count();
		if (n==0) return Collections.EMPTY_LIST;
		if (n==1) return Collections.singletonList(Sets.empty());
		ArrayList<ASet> al=new ArrayList<>();
		
		for (int i=0; i<n; i++) {
			al.add(v.exclude(v.get(i)));
		}
		
		Collections.sort(al,Cells.countComparator);
		
		return al;
	}
}
