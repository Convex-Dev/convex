package convex.net;

import convex.core.data.Keyword;

/**
 * Constant tags used to identify general purpose messages
 */
public class MessageTag {

	public static Keyword QUERY=Keyword.intern("Q");
	public static Keyword TRANSACT=Keyword.intern("TX");
	
	public static Keyword DATA_QUERY=Keyword.intern("DQ");
	public static Keyword DATA_RESPONSE=Keyword.intern("DR");

}
