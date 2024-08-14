package convex.core.lang.impl;

import convex.core.data.ACell;

/**
 * Abstract base class for exceptional return values.
 * 
 * Java exceptions are expensive and don't make it easy to provide exactly the
 * semantics we want so we return exceptional values in response to errors
 * during on-chain execution.
 * 
 * Notable uses: - Early return values from functions - Tail calls - Loop /
 * recur
 * 
 * "Do not fear to be eccentric in opinion, for every opinion now accepted was
 * once eccentric." ― Bertrand Russell
 */
public abstract class AExceptional {

	/**
	 * Returns the Exception code for this exceptional value
	 * @return Exception Code
	 */
	public abstract ACell getCode();

	/**
	 * Gets the message for an exceptional value. May or may not be meaningful.
	 * @return Exception Message
	 */
	public abstract ACell getMessage();

	/**
	 * Return true if this exceptional value is catchable
	 * @return
	 */
	public boolean isCatchable() {
		return false;
	}

}
