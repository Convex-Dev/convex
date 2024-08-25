package convex.core;

import convex.core.data.Keyword;

/**
 * Standard codes used for CVM Result sources. See CAD11 Error Sources 
 * 
 * An Exceptional Condition may include a Message, which is kept outside CVM state but may be user to return 
 * information to the relevant client.
 */
public class SourceCodes {

	/**
	 * Source code indicating client code made an error
	 */
	public static final Keyword CLIENT = Keyword.create("CLIENT");
	
	/**
	 * Source code indicating a failure due to communication, IO or timeout
	 */
	public static final Keyword COMM = Keyword.create("COMM");
	
	/**
	 * Source code indicating a failure during peer handling
	 */
	public static final Keyword PEER = Keyword.create("PEER");
	
	/**
	 * Source code indicating a failure trying to reach consensus
	 */
	public static final Keyword NET = Keyword.create("NET");
	
	/**
	 * Source code indicating a failure in CVM transaction handling
	 */
	public static final Keyword CVM = Keyword.create("CVM");
	
	/**
	 * Source code indicating a failure in CVM transaction handling
	 */
	public static final Keyword CODE = Keyword.create("CODE");




}
