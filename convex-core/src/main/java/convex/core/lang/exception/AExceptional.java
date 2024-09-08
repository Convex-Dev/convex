package convex.core.lang.exception;

import convex.core.data.ACell;

/**
 * Abstract base class for exceptional return values.
 * 
 * Java exceptions are expensive and don't make it easy to provide exactly the
 * semantics we want so we return exceptional values in response to errors
 * during on-chain execution.
 * 
 * This can be considered an application of the principle of "errors as values".
 * 
 * Notable uses: - Early return values from functions - Tail calls - Loop /
 * recur
 * 
 * "Do not fear to be eccentric in opinion, for every opinion now accepted was
 * once eccentric." ― Bertrand Russell
 */
public abstract class AExceptional {

	/**
	 * Returns the Error code for this exceptional value, as defined in CAD11
	 * 
	 * The Error Code may be any value, but
	 * by convention (and exclusively in Convex runtime code) it is a upper-case keyword e.g. :ASSERT
	 * 
	 * @return Error code value
	 */
	public abstract ACell getCode();

	/**
	 * Gets the message for an exceptional value.
	 * @return Exception Message
	 */
	public abstract ACell getMessage();

	/**
	 * Return true if this exceptional value is catchable
	 * @return true iff the exception can be caught (user :CODE exceptions)
	 */
	public boolean isCatchable() {
		return false;
	}

}
