package convex.core.data;

import java.util.function.Consumer;

/**
 * BAse class for specialised vector implementations
 */
public abstract class ASpecialVector<T extends ACell> extends AVector<T> {

	public ASpecialVector(long count) {
		super(count);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected void visitAllChildren(Consumer<AVector<T>> visitor) {
		((AVector<T>)getCanonical()).visitAllChildren(visitor);
	}

}
