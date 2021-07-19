package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * Type that represents any CVM sequence
 */
@SuppressWarnings("rawtypes")
public class Sequence extends AStandardType<ASequence> {

	public static final Sequence INSTANCE = new Sequence();
	
	private Sequence() {
		super (ASequence.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ASequence);
	}

	@Override
	public String toString() {
		return "Sequence";
	}

	@Override
	public AVector<?> defaultValue() {
		return Vectors.empty();
	}
	
	@Override
	public ASequence<?> implicitCast(ACell a) {
		if (a instanceof ASequence) return (ASequence<?>)a;
		return null;
	}
}
