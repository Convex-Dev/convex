package convex.core.cvm.exception;

import convex.core.data.ACell;

/**
 * Abstract base class for trampolining function return values
 */
public abstract class ATrampoline extends AReturn {
	
	protected final ACell[] args;

	public ATrampoline(ACell[] values) {
		this.args=values;
	}

	public ACell getValue(int i) {
		return args[i];
	}

	public ACell[] getValues() {
		return args;
	}

	public int arity() {
		return args.length;
	}

}
