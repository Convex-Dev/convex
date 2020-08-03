package convex.core.lang.impl;

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
	 * Returns the Error code for this exceptional value
	 * @return
	 */
	public abstract Object getCode();

}
