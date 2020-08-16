package convex.core;

import convex.core.data.Keyword;

/**
 * Standard codes used for CVM Exceptional Conditions.
 * 
 * An Exceptional Condition may include a Message, which is kept outside CVM state but may be user to return 
 * information to the relevant client.
 */
public class ErrorCodes {
	/**
	 * Error code for a bad sequence.
	 * The message is expected to be the current sequence number.
	 */
	public static final Keyword SEQUENCE = Keyword.create("SEQUENCE");
	
	/**
	 * Error code for when the specified account does not have enough available funds to perform an operation
	 */
	public static final Keyword FUNDS = Keyword.create("FUNDS");
	
	/**
	 * Error code for when a transaction runs out of available juice
	 */
	public static final Keyword JUICE = Keyword.create("JUICE");
	
	/**
	 * Error code for when a transactione exceeds execution depth limits
	 */
	public static final Keyword DEPTH = Keyword.create("DEPTH");
	
	/**
	 * Error code for situations where a transaction is unable to complete due to insufficient 
	 * Memory Allowance
	 */
	public static final Keyword MEMORY = Keyword.create("MEMORY");
	
	/**
	 * Error code when attempting to perform an action using a non-existent Account
	 */
	public static final Keyword NOBODY = Keyword.create("NOBODY");
	
	/**
	 * Error code when function or expander application has an inappropriate number of arguments.
	 * Arity is checked first: it takes precedence over CAST and ARGUMENT errors.
	 */
	public static final Keyword ARITY = Keyword.create("ARITY");
	
	/**
	 * Error code when an undeclared symbol is accessed
	 */
	public static final Keyword UNDECLARED = Keyword.create("UNDECLARED");
	
	/**
	 * Error code when the type of some argument cannot be cast to a suitable type for
	 * some requested operation. ARITY errors take predecence over CAST errors if both
	 * are applicable.
	 */
	public static final Keyword CAST = Keyword.create("CAST");
	
	/**
	 * Error code for when access is attmpted that is out of bounds for some sequential object.
	 * 
	 */
	public static final Keyword BOUNDS = Keyword.create("BOUNDS");
	
	/**
	 * Error code for when an argument is of the correct type, but is not an allowable value.
	 */
	public static final Keyword ARGUMENT = Keyword.create("ARGUMENT");
	
	/**
	 * Error code for a request that would normally be valid, but failed because some aspect of
	 * actor / system state was wrong. Typically indicates that some preparatory step was omitted,
	 * appropriate pre-conditions were not checked, or an operation was attempted at an inappropriate time
	 */
	public static final Keyword STATE = Keyword.create("STATE");
	
	/**
	 * Error code caused by compilation failure with an invalid AST. Should only occur during
	 * compile phase of on-chain Compiler
	 */
	public static final Keyword COMPILE = Keyword.create("COMPILE");
	
	/**
	 * Error code caused by failure to successfully expand an AST node. Should only occur during
	 * expand phase of on-chain Compiler
	 */
	public static final Keyword EXPAND = Keyword.create("EXPAND");

	/**
	 * Error code indicating that an asserted condition was not met. This usually indicates invalid
	 * input that failed a precondition check. The message should be used to give meaningful feedback to
	 * the User.
	 */
	public static final Keyword ASSERT = Keyword.create("ASSERT");

	public static final Keyword UNEXPECTED = Keyword.create("UNEXPECTED");

	// Error codes for non-error values
	
	/**
	 * Exceptional Condition indicating a halt operation was executed.
	 * 
	 * This will halt the currently executing transaction context and return to the caller.
	 */
	public static final Keyword HALT = Keyword.create("HALT");
	
	/**
	 * Exceptional Condition indicating a recur operation was executed
	 * 
	 * This will return execution to the surrounding loop or function binding, which will be 
	 * re-executed with new bindings provided to the recur operation.
	 */
	public static final Keyword RECUR = Keyword.create("RECUR");
	
	/**
	 * Exceptional Condition indicating a return operation was executed
	 * 
	 * This will return execution to the caller of surrounding function binding, with whatever
	 * value is passed to the return operation as a result.
	 */
	public static final Keyword RETURN = Keyword.create("RETURN");
	
	/**
	 * Exceptional Condition indicating a halt operation was executed.
	 * 
	 * This will terminate the currently executing transaction context, roll back any state changes
	 * and return to the caller with whatever value is passed as the rollback result.
	 */
	public static final Keyword ROLLBACK =  Keyword.create("ROLLBACK");
}
