package convex.core.data;

import java.util.function.Consumer;

/**
 * Base class for specialised vector implementations
 */
public abstract class ASpecialVector<T extends ACell> extends AVector<T> {

	public ASpecialVector(long count) {
		super(count);
	}

	/**
	 * Appends to the canonical vector. Implemented here so that a subclass fixing
	 * the element type inherits it: an override there would cast every value to that
	 * type, where a vector accepts any value.
	 */
	@Override
	public AVector<T> append(T value) {
		return toVector().append(value);
	}

	/** Associates in the canonical vector. Implemented here for the reason given on {@link #append(ACell)}. */
	@Override
	public AVector<T> assoc(long i, T value) {
		return toVector().assoc(i,value);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void visitAllChildren(Consumer<AVector<T>> visitor) {
		((AVector<T>)getCanonical()).visitAllChildren(visitor);
	}

}
