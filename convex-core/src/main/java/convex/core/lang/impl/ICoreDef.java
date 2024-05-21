package convex.core.lang.impl;

import convex.core.data.Symbol;

/**
 * Interface for objects that act as definitions in the core environment.
 * 
 */
public interface ICoreDef {

	/**
	 * Defines the symbol for this core definition.
	 * 
	 * @return The symbol for this core definition.
	 */
	public Symbol getSymbol();
	
	/**
	 * Defines the symbol for this core definition.
	 * 
	 * @return The symbol for this core definition.
	 */
	public Symbol getIntrinsicSymbol();

	/**
	 * Gets the core definition code for this value
	 * @return Code definition code
	 */
	public int getCoreCode();

}
