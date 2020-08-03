package convex.core;

import convex.core.data.Keyword;

public class ErrorCodes {
	public static final Keyword SEQUENCE = Keyword.create("SEQUENCE");
	
	public static final Keyword FUNDS = Keyword.create("FUNDS");
	public static final Keyword JUICE = Keyword.create("JUICE");
	public static final Keyword DEPTH = Keyword.create("DEPTH");
	public static final Keyword MEMORY = Keyword.create("MEMORY");
	
	public static final Keyword NOBODY = Keyword.create("NOBODY");
	public static final Keyword ARITY = Keyword.create("ARITY");
	public static final Keyword UNDECLARED = Keyword.create("UNDECLARED");
	public static final Keyword CAST = Keyword.create("CAST");
	public static final Keyword BOUNDS = Keyword.create("BOUNDS");
	public static final Keyword ARGUMENT = Keyword.create("ARGUMENT");
	public static final Keyword STATE = Keyword.create("STATE");
	public static final Keyword COMPILE = Keyword.create("COMPILE");
	public static final Keyword EXPAND = Keyword.create("EXPAND");
	public static final Keyword ASSERT = Keyword.create("ASSERT");

	public static final Keyword UNEXPECTED = Keyword.create("UNEXPECTED");

	// Error codes for non-error values
	public static final Keyword HALT = Keyword.create("HALT");
	public static final Keyword RECUR = Keyword.create("RECUR");
	public static final Keyword RETURN = Keyword.create("RETURN");
	public static final Keyword ROLLBACK =  Keyword.create("ROLLBACK");
}
