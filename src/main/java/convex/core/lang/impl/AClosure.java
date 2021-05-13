package convex.core.lang.impl;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Symbol;
import convex.core.lang.AFn;

/**
 * Abstract base class for functions that can close over a lexical enviornment.
 *
 * @param <T> Return type of function
 */
public abstract class AClosure<T extends ACell> extends AFn<T> {
	/**
	 * Lexical environment saved for this closure
	 */
	protected final AHashMap<Symbol, ACell> lexicalEnv;

	protected AClosure(AHashMap<Symbol, ACell> lexicalEnv) {
		this.lexicalEnv=lexicalEnv;
	}
	
	/**
	 * Produces an copy of this closure with the specified environment
	 * 
	 * @param env New lexical environment to use for this closure
	 * @return Closure updated with new lexical environment
	 */
	public abstract <F extends AClosure<T>> F withEnvironment(AHashMap<Symbol, ACell> env);
	

	/**
	 * Print the "internal" representation of a closure e.g. "[x] 1", excluding the 'fn' symbol.
	 * @param sb
	 */
	public abstract void printInternal(StringBuilder sb);
}
