package convex.core.data;

/**
 * BAse class for specialised vector implementations
 */
public abstract class ASpecialVector<T extends ACell> extends AVector<T> {

	public ASpecialVector(long count) {
		super(count);
	}

}
