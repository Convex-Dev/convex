package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.Vectors;

/**
 * Type that represents any CVM sequence
 */
@SuppressWarnings("rawtypes")
public class DataStructure extends AStandardType<ADataStructure> {

	public static final DataStructure INSTANCE = new DataStructure();
	
	private DataStructure() {
		super(ADataStructure.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ADataStructure);
	}

	@Override
	public String toString() {
		return "DataStructure";
	}

	@Override
	public ADataStructure<?> defaultValue() {
		return Vectors.empty();
	}
	
	@Override
	public ADataStructure<?> implicitCast(ACell a) {
		if (a instanceof ADataStructure) return (ADataStructure<?>)a;
		return null;
	}

}
