package convex.core.lang.impl;

import convex.core.data.IObject;
import convex.core.data.Symbol;

/**
 * Interface for objects that act as definitions in the core environment.
 * 
 * These are serialised as symbolic references, and will be deserialised to
 * point to the same core object.
 */
public interface ICoreDef extends IObject {

	/**
	 * Defines the symbol for this core definition.
	 * 
	 * @return The symbol for this core definition.
	 */
	public Symbol getSymbol();
}
