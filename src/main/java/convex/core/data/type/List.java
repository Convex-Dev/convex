package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AList;

/**
 * Type that represents any CVM collection
 */
public class List extends AType {

	public static final List INSTANCE = new List();
	
	private List() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof AList);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "List";
	}

}
