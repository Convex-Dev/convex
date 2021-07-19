package convex.core.data.type;

import convex.core.data.ACell;

/**
 * Base type for standard types mapped directly to a branch of ACell hierarchy
 * @param <T>
 */
public abstract class AStandardType<T extends ACell> extends AType {

	Class<T> klass;
	
	protected AStandardType(Class<T> klass) {
		this.klass=klass;
	}
	
	@Override
	public boolean check(ACell value) {
		return klass.isInstance(value);
	}

	@Override
	public final boolean allowsNull() {
		return false;
	}
	
	@Override
	public abstract T defaultValue();

	@SuppressWarnings("unchecked")
	@Override
	public T implicitCast(ACell a) {
		if (check(a)) return (T)a;
		return null;
	}

	@Override
	public final Class<T> getJavaClass() {
		return klass;
	}

}
