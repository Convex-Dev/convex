package convex.core.data;

/**
 * Abstract base class for vectors with a count
 *
 * @param <T>
 */
public abstract class ASizedVector<T extends ACell> extends AVector<T> {

	protected final long count;
	
	protected ASizedVector(long count) {
		this.count=count;
	}
	
	@Override
	public final long count() {
		return count;
	}
}
