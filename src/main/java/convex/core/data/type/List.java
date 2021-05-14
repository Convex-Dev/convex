package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.Lists;

/**
 * Type that represents any CVM collection
 */
@SuppressWarnings("rawtypes")
public class List extends AStandardType<AList> {

	public static final List INSTANCE = new List();
	
	private List() {
		super(AList.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof AList);
	}

	@Override
	public String toString() {
		return "List";
	}

	@Override
	public AList<?> defaultValue() {
		return Lists.empty();
	}

	@Override
	public AList<?> implicitCast(ACell a) {
		if (a instanceof AList) return (AList<?>)a;
		return null;
	}
}
