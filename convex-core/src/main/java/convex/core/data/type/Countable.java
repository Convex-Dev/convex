package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ACountable;

@SuppressWarnings("rawtypes")
public class Countable extends AStandardType<ACountable> {
	public static final Countable INSTANCE = new Countable();
	
	private Countable() {
		super(ACountable.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ACountable);
	}

	@Override
	public String toString() {
		return "Countable";
	}

	@Override
	public ACountable<?> defaultValue() {
		return convex.core.data.Blob.EMPTY;
	}
	
	@Override
	public ACountable<?> implicitCast(ACell a) {
		if (a instanceof ACountable) return (ACountable<?>)a;
		return null;
	}

}
