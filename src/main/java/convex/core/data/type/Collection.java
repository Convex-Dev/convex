package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.Vectors;

/**
 * Type that represents any CVM collection
 */
public class Collection extends AType {

	public static final Collection INSTANCE = new Collection();
	
	private Collection() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ACollection);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Collection";
	}

	@Override
	protected ACollection<?> defaultValue() {
		return Vectors.empty();
	}

	@Override
	protected ACollection<?> implicitCast(ACell a) {
		if (a instanceof ACollection) return (ACollection<?>)a;
		return null;
	}

}
