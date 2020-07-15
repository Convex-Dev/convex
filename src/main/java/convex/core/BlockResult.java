package convex.core;

import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.lang.impl.ErrorValue;

/**
 * Class representing the result of applying a Block to a State.
 * 
 * Each transaction in the block has a corresponding result entry, which may
 * either be a valid result or an error.
 *
 */
public class BlockResult {
	private State state;
	private AVector<Object> results;

	private BlockResult(State state, AVector<Object> results) {
		this.state = state;
		this.results = results;
	}

	public static BlockResult create(State state, Object[] results) {
		return new BlockResult(state, Vectors.of(results));
	}

	public State getState() {
		return state;
	}

	public AVector<Object> getResults() {
		return results;
	}
	
	public boolean isError(long i) {
		return getResult(i) instanceof ErrorValue;
	}

	public Object getResult(long i) {
		return results.get(i);
	}

	/**
	 * Gets the error value for a given transaction
	 * @param i
	 * @return ErrorValue instance, or null if the transaction succeeded.
	 */
	public ErrorValue getError(long i) {
		Object result=results.get(i);
		if (!(result instanceof ErrorValue)) return null;
		return (ErrorValue) result;
	}
}
