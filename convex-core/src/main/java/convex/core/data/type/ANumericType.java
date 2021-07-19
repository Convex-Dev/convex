package convex.core.data.type;

import convex.core.data.prim.APrimitive;

public abstract class ANumericType<T extends APrimitive> extends AStandardType<T> {

	protected ANumericType(Class<T> klass) {
		super(klass);
	}

}
