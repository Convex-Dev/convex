package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.MapEntry;

/**
 * Generator for arbitrary CAD3 data structures
 */
public class DataStructureGen extends AGenerator<ADataStructure<ACell>> {
	@SuppressWarnings("rawtypes")
	private static final Class cls = (Class) ADataStructure.class;

	@SuppressWarnings("unchecked")
	public DataStructureGen() {
		super(cls);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ADataStructure<ACell> generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(5);

		switch (type) {
		case 0:
			return Gen.HASHMAP.generate(r, status);
		case 1:
			return Gen.VECTOR.generate(r, status);
		case 2:
			return Gen.LIST.generate(r, status);
		case 3:
			return Gen.SET.generate(r, status);

		// generate map entries as special cases of vectors
		case 4: {
			Generator<ACell> vgen = Gen.VALUE;
			return MapEntry.create(vgen.generate(r, status), vgen.generate(r, status));
		}
		}
		throw new Error("Bad Type!");
	}
}
