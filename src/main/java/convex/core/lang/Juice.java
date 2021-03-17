package convex.core.lang;

/**
 * Static class defining juice costs for executable operations.
 * 
 * "LISP programmers know the value of everything and the cost of nothing." -
 * Alan Perlis
 * 
 */
public class Juice {
	/**
	 * Juice required to define a value in the current environment.
	 * 
	 * We make this somewhat expensive - we want to discourage over-use as a general rule
	 * since it writes to global chain state. However memory accounting helps discourage 
	 * superfluous defs, so it only needs to reflect execution cost.
	 */
	public static final long DEF = 100;

	/**
	 * Juice required to look up a value in the local environment.
	 */
	public static final long LOOKUP = 15;
	
	/**
	 * Juice required to look up a value in the dynamic environment.
	 * 
	 * Potentially a bit pricey since read only, but might hit storage so.....
	 */
	public static final long LOOKUP_DYNAMIC = 40;

	/**
	 * Juice required to execute a Do block
	 * 
	 * Very cheap, no allocs.
	 */
	public static final long DO = 10;

	/**
	 * Juice required to execute a Return op
	 * 
	 * Pretty cheap, one alloc and a bit of exceptional value handling.
	 */
	public static final long RETURN = 20;

	/**
	 * Juice required to execute a Let block
	 * 
	 * Fairly cheap but some parameter munging required. Might revisit binding
	 * costs?
	 */
	public static final long LET = 30;

	/**
	 * Juice required to resolve a constant value
	 * 
	 * Very cheap, no allocs / lookup.
	 */
	public static final long CONSTANT = 10;

	/**
	 * Juice required to execute a Cond expression
	 * 
	 * Pretty cheap, nothing nasty here (though conditions / results themselves
	 * might get pricey).
	 */
	public static final long COND_OP = 20;

	/**
	 * Juice required to create a lambda
	 * 
	 * Sort of expensive - might allocate a bunch of stuff for the closure?
	 */
	public static final long LAMBDA = 100;

	/**
	 * Juice required to call an Actor
	 * 
	 * Slightly expensive for context switching?
	 */
	public static final long CALL_OP = 100;

	/**
	 * Juice required to build a data structure. Make a bit expensive?
	 */
	protected static final long BUILD_DATA = 50;

	/**
	 * Juice required per element changed when building a data structure. Map entries
	 * count as two elements.
	 * 
	 * We need to be a bit harsh on this! Risk of consuming too much heap space,
	 * might also result in multiple allocs for tree structures.
	 */
	protected static final long BUILD_PER_ELEMENT = 50;

	protected static final long MAP = 100;
	protected static final long REDUCE = 100;

	/**
	 * Juice for general object equality comparison
	 * 
	 * Pretty cheap.
	 */
	public static final long EQUALS = 20;

	/**
	 * Juice for numeric comparison
	 * 
	 * Pretty cheap. Bit of casting perhaps.
	 */
	public static final long NUMERIC_COMPARE = 20;

	/**
	 * Juice for an apply operation
	 * 
	 * Bit of cost to allow for parameter construction. Might need to revisit for
	 * bigger sequences?
	 */
	public static final long APPLY = 50;

	/**
	 * Juice for a cryptographic hash
	 * 
	 * Expensive.
	 */
	public static final long HASH = 10000;

	/**
	 * Juice for a very cheap operation. O(1), no new cell allocations or non-trivial lookups.
	 */
	public static final long CHEAP_OP = 10;
	
	/**
	 * Juice for a simple built-in core function. Simple operations are assumed to
	 * require no expensive resource access, and operate with O(1) allocations
	 */
	public static final long SIMPLE_FN = 20;

	/**
	 * Juice for constructing a String
	 * 
	 * Fairly cheap, since mostly in fast code, but charge extra for additional
	 * chars.
	 */
	protected static final long STR = SIMPLE_FN;
	protected static final long STR_PER_CHAR = 5;

	/**
	 * Juice for storing a new constant value permanently in on-chain state Charged
	 * per node stored
	 */
	protected static final long STORE = 1000;

	protected static final long FETCH = 100;

	protected static final long ARITHMETIC = SIMPLE_FN;

	protected static final long ADDRESS = 100;

	protected static final long BALANCE = 200;

	/**
	 * Juice for creation of a blob
	 */
	protected static final long BLOB = 100;
	protected static final long BLOB_PER_BYTE = 1;

	protected static final long GET = 30;

	protected static final long KEYWORD = 50;

	protected static final long SYMBOL = 50;

	public static final long TRANSFER = 100;

	public static final long SIMPLE_MACRO = 200;

	/**
	 * Juice for a recur form
	 * 
	 * Fairly cheap, might have to construct some temp structures for recur
	 * arguments.
	 */
	public static final long RECUR = 30;

	/**
	 * Juice for a contract deployment
	 * 
	 * Make this quite expensive, mainly to deter lots of willy-nilly deploying
	 */
	public static final long DEPLOY_CONTRACT = 1000;

	/**
	 * Probably should be expensive?
	 */
	protected static final long EVAL = 500;

	// Juice amounts for compiler. TODO: figure out if compile / eval should be
	// allowed on-chain

	public static final long COMPILE_CONSTANT = 30;

	public static final long COMPILE_LOOKUP = 50;

	public static final long COMPILE_NODE = 200;

	public static final long EXPAND_CONSTANT = 40;

	public static final long EXPAND_SEQUENCE = 100;

	public static final long SCHEDULE = 800;

	public static final long SCHEDULE_EXPAND = 200;

	/**
	 * Default future schedule juice (10 per hour)
	 * 
	 * This makes scheduling a few hours / days ahead cheap but year is quite
	 * expensive (~87,600). Also places an upper bound on advance schedules.
	 * 
	 * TODO: review this
	 */
	public static final long SCHEDULE_MILLIS_PER_JUICE_UNIT = 360000;

	public static final long ROLLBACK = 50;

	public static final long HALT = 50;
	
	protected static final long REDUCED =50;

	/**
	 * Juice cost for accepting an offer of crypto funds.
	 * 
	 * We make this a little expensive because it involves updating two separate accounts.
	 */
	public static final long ACCEPT = 100;

	/**
	 * Juice cost for constructing a Syntax Object. Fairly lightweight.
	 */
	public static final long SYNTAX = Juice.SIMPLE_FN;

	/**
	 * Juice cost for extracting metadata from a Syntax object.
	 */
	public static final long META = Juice.CHEAP_OP;

	public static final long ASSOC = Juice.BUILD_DATA+Juice.BUILD_PER_ELEMENT*2;

	public static final long SET_COMPARE_PER_ELEMENT = 10;

	public static final long CREATE_ACCOUNT = 100;

	public static final long QUERY = Juice.CHEAP_OP;

	protected static final long LOG = 100;

	/**
	 * Saturating multiply and add result = a + b * c
	 * 
	 * Returns Long.MAX_VALUE on overflow.
	 * 
	 * @param juice
	 * @param size
	 * @param buildPerElement
	 * @return
	 */
	public static final long addMul(long a, long b, long c) {
		return add(a,mul(b,c));
	}
	
	/**
	 * Saturating multiply. Returns Long.MAX_VALUE on overflow.
	 * @param a
	 * @param b
	 * @return
	 */
	public static final long mul(long a, long b) {
		if ((a<0)||(b<0)) return Long.MAX_VALUE;
		if (Math.multiplyHigh(a, b)>0) return Long.MAX_VALUE;
		return a*b;
	}
	
	/**
	 * Saturating addition. Returns Long.MAX_VALUE on overflow.
	 * @param a
	 * @param b
	 * @return
	 */
	public static final long add(long a, long b) {
		if ((a<0)||(b<0)) return Long.MAX_VALUE;
		if ((a+b)<0) return Long.MAX_VALUE;
		return a+b;
	}


}
