package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * Type that represents any CVM collection
 */
public class Vector extends AType {

	public static final Vector INSTANCE = new Vector();
	
	private Vector() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof AVector);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Vector";
	}

	@Override
	protected AVector<?> defaultValue() {
		return Vectors.empty();
	}
	
	@Override
	protected AVector<?> implicitCast(ACell a) {
		if (a instanceof AVector) return (AVector<?>)a;
		return null;
	}

}
