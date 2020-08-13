package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ADataStructure;
import convex.core.data.MapEntry;

/**
 * Generator for arbitrary collections
 */
public class DataStructureGen extends Generator<ADataStructure<?>> {
	@SuppressWarnings("rawtypes")
	private static final Class cls = (Class) ADataStructure.class;

	@SuppressWarnings("unchecked")
	public DataStructureGen() {
		super(cls);
	}

	@Override
	public ADataStructure<?> generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(5);

		switch (type) {
		case 0:
			return gen().make(MapGen.class).generate(r, status);
		case 1:
			return gen().make(VectorGen.class).generate(r, status);
		case 2:
			return gen().make(ListGen.class).generate(r, status);
		case 3:
			return gen().make(SetGen.class).generate(r, status);

		// generate map entries as special cases of vectors
		case 4: {
			Generator<Object> vgen = gen().make(ValueGen.class);
			return MapEntry.create(vgen.generate(r, status), vgen.generate(r, status));
		}
		}
		throw new Error("Bad Type!");
	}
}
