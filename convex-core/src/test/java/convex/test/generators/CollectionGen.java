package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.ACollection;

/**
 * Generator for arbitrary collections
 */
public class CollectionGen extends AGenerator<ACollection<ACell>> {
	@SuppressWarnings("rawtypes")
	private static final Class cls = (Class) ACollection.class;

	@SuppressWarnings("unchecked")
	public CollectionGen() {
		super(cls);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ACollection<ACell> generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(3);
		switch (type) {
		case 0:
			return Gen.VECTOR.generate(r, status);
		case 1:
			return Gen.LIST.generate(r, status);
		case 2:
			return Gen.SET.generate(r, status);

		default:
			throw new Error("Unexpected type: " + type);
		}
	}
	
    @Override public int numberOfNeededComponents() {
        return 1;
    }
}
