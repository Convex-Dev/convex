package convex.core.lang.impl;

import convex.core.data.AHashMap;
import convex.core.data.Symbol;
import convex.core.lang.AFn;

/**
 * Abstract base class for functions that can close over a lexical enviornment.
 *
 * @param <T> Return type of function
 */
public abstract class AClosure<T> extends AFn<T> {
	/**
	 * Lexical environment saved for this closure
	 */
	protected final AHashMap<Symbol, Object> lexicalEnv;

	protected AClosure(AHashMap<Symbol, Object> lexicalEnv) {
		this.lexicalEnv=lexicalEnv;
	}
	
	/**
	 * Produces an copy of this closure with the specified environment
	 * 
	 * @param env New lexical environment to use for this closure
	 * @return Closure updated with new lexical environment
	 */
	public abstract <F extends AClosure<T>> F withEnvironment(AHashMap<Symbol, Object> env);
}
