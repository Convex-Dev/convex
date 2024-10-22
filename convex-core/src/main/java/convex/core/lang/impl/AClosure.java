package convex.core.lang.impl;

import convex.core.cvm.AFn;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.util.BlobBuilder;

/**
 * Abstract base class for functions that can close over a lexical environment.
 *
 * @param <T> Return type of function
 */
public abstract class AClosure<T extends ACell> extends AFn<T> {
	/**
	 * Lexical environment saved for this closure
	 */
	protected final AVector<ACell> lexicalEnv;

	protected AClosure(AVector<ACell> lexicalEnv) {
		this.lexicalEnv=lexicalEnv;
	}
	
	/**
	 * Produces an copy of this closure with the specified environment
	 * 
	 * @param env New lexical environment to use for this closure
	 * @return Closure updated with new lexical environment
	 */
	public abstract <F extends AClosure<T>> F withEnvironment(AVector<ACell> env);
	
	/**
	 * Print the "internal" representation of a closure e.g. "[x] 1", excluding the 'fn' symbol.
	 * @param sb StringBuilder to print to
	 * @param limit Limit of BlobBuilder size
	 * @return True if printed successfully within limit, false otherwise
	 */
	public abstract boolean printInternal(BlobBuilder sb, long limit);

}
