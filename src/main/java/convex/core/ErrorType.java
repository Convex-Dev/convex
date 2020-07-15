package convex.core;

/**
 * Enum for all possible error types produced in on-chain execution.
 * 
 * "Only two things are infinite, the universe and human stupidity, and I'm not
 * sure about the former." - Albert Einstein
 */
public enum ErrorType {
	/**
	 * Error that indicates an invalid argument was used.
	 * 
	 */
	ARGUMENT(ErrorCodes.ARGUMENT),

	/**
	 * Error type that indicates an illegal number of arguments was passed to a
	 * function.
	 */
	ARITY(ErrorCodes.ARITY),

	/**
	 * Error type that indicates that a bounds check failed on a sequential data
	 * structure
	 */
	BOUNDS(ErrorCodes.BOUNDS),

	/**
	 * Error type that indicates that a conversion between types occurred
	 */
	CAST(ErrorCodes.CAST),

	/**
	 * Error type that indicates that a compile error occurred
	 */
	COMPILE(ErrorCodes.COMPILE),

	/**
	 * Error type that indicates that a expander error occurred
	 */
	EXPAND(ErrorCodes.EXPAND),

	/**
	 * Error type that indicates an operation was performed that encountered an
	 * illegal state
	 */
	STATE(ErrorCodes.STATE),

	/**
	 * Error type that indicates an account had insufficient funds to perform a
	 * specified operation
	 * 
	 * May be the result of: - Insufficient funds for juice at the start of a
	 * transaction - Attempt to transfer funds beyond current balance
	 */
	FUNDS(ErrorCodes.FUNDS),

	/**
	 * Error type that indicates an attempt to use an account that does not exist.
	 */
	NOBODY(ErrorCodes.NOBODY),

	/**
	 * Error type that indicates a stack overflowing the maximum allowable depth.
	 */
	DEPTH(ErrorCodes.DEPTH),

	/**
	 * Error type that indicates attempt to resolve an undeclared symbol.
	 */
	UNDECLARED(ErrorCodes.UNDECLARED),

	/**
	 * Error type that indicates attempt to resolve an undeclared symbol.
	 */
	SEQUENCE(ErrorCodes.SEQUENCE),

	/**
	 * Error type that indicates insufficient juice to complete an operation.
	 */
	JUICE(ErrorCodes.JUICE),

	/**
	 * Error type that indicates a failed assertion
	 */
	ASSERT(ErrorCodes.ASSERT),
	
	/**
	 * Error type that indicates memory bounds were exceeded
	 */
	MEMORY(ErrorCodes.MEMORY),

	/**
	 * Error type that indicates an unexpected exceptional value
	 */
	UNEXPECTED(ErrorCodes.UNEXPECTED)

	;

	private final byte code;

	public byte getErrorCode() {
		return code;
	}

	/**
	 * Decodes an error value to obtain the appropriate ErrorType enumeration instance.
	 * 
	 * @param errorCode Error code as an integer value
	 * @return ErrorType instance, or null if ErrorType was not recognised.
	 */
	public static ErrorType decode(long errorCode) {
		switch ((int) errorCode) {
		case ErrorCodes.ARGUMENT:
			return ARGUMENT;
		case ErrorCodes.ARITY:
			return ARITY;
		case ErrorCodes.BOUNDS:
			return BOUNDS;
		case ErrorCodes.CAST:
			return CAST;
		case ErrorCodes.COMPILE:
			return COMPILE;
		case ErrorCodes.EXPAND:
			return EXPAND;
		case ErrorCodes.FUNDS:
			return FUNDS;
		case ErrorCodes.NOBODY:
			return NOBODY;
		case ErrorCodes.SEQUENCE:
			return SEQUENCE;
		case ErrorCodes.STATE:
			return STATE;
		case ErrorCodes.DEPTH:
			return DEPTH;
		case ErrorCodes.UNDECLARED:
			return UNDECLARED;
		case ErrorCodes.JUICE:
			return JUICE;
		case ErrorCodes.ASSERT:
			return ASSERT;
		case ErrorCodes.MEMORY:
			return MEMORY;
		case ErrorCodes.UNEXPECTED:
			return UNEXPECTED;
		}
		return null;
	}

	private ErrorType(int i) {
		this.code = (byte) i;
		if (i != code) throw new Error("Error byte out of range: " + i);
	}

	public byte code() {
		return code;
	}

}
