package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.data.ACountable;

@SuppressWarnings("rawtypes")
public class Countable extends AStandardType<ACountable> {
	public static final Countable INSTANCE = new Countable();
	
	private Countable() {
		super(ACountable.class);
	}

	@Override
	public boolean check(ACell value) {
		return RT.ensureCountable(value)!=null;
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
		return RT.ensureCountable(a);
	}

}
