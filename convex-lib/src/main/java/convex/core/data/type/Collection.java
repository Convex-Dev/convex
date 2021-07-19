package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.Vectors;

/**
 * Type that represents any CVM collection
 */
@SuppressWarnings("rawtypes")
public class Collection extends AStandardType<ACollection> {

	public static final Collection INSTANCE = new Collection();
	
	private Collection() {
		super (ACollection.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ACollection);
	}

	@Override
	public String toString() {
		return "Collection";
	}

	@Override
	public ACollection<?> defaultValue() {
		return Vectors.empty();
	}

	@Override
	public ACollection<?> implicitCast(ACell a) {
		if (a instanceof ACollection) return (ACollection<?>)a;
		return null;
	}
}
