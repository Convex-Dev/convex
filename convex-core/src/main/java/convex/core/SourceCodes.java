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
	public static final Keyword CLIENT = Keyword.intern("CLIENT");
	
	/**
	 * Source code indicating a failure due to communication, IO or timeout
	 */
	public static final Keyword COMM = Keyword.intern("COMM");
	
	/**
	 * Source code indicating an error at the server side (e.g. REST Server)
	 */
	public static final Keyword SERVER = Keyword.intern("SERVER");

	/**
	 * Source code indicating a failure during peer handling
	 */
	public static final Keyword PEER = Keyword.intern("PEER");
	
	/**
	 * Source code indicating a failure trying to reach consensus
	 */
	public static final Keyword NET = Keyword.intern("NET");
	
	/**
	 * Source code indicating a failure in CVM transaction handling
	 */
	public static final Keyword CVM = Keyword.intern("CVM");
	
	/**
	 * Source code indicating a failure in CVM transaction handling
	 */
	public static final Keyword CODE = Keyword.intern("CODE");





}
