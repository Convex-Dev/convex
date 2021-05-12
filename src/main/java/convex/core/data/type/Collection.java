package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ACollection;

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
		return true;
	}

	@Override
	public String toString() {
		return "Collection";
	}

}
