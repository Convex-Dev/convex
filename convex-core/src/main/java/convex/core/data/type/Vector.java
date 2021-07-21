package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * Type that represents any CVM collection
 */
@SuppressWarnings("rawtypes")
public class Vector extends AStandardType<AVector> {

	public static final Vector INSTANCE = new Vector();
	
	private Vector() {
		super(AVector.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof AVector);
	}


	@Override
	public String toString() {
		return "Vector";
	}

	@Override
	public AVector<?> defaultValue() {
		return Vectors.empty();
	}
	
	@Override
	public AVector implicitCast(ACell a) {
		if (a instanceof AVector) return (AVector)a;
		return null;
	}
}
