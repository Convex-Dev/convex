package convex.net;

import convex.core.data.Keyword;

/**
 * Constant tags used to identify general purpose messages
 */
public class MessageTag {

	public static final Keyword STATUS_REQUEST = Keyword.intern("SR");
	public static final Keyword QUERY=Keyword.intern("Q");
	public static final Keyword TRANSACT=Keyword.intern("TX");
	
	public static final Keyword DATA_QUERY=Keyword.intern("DQ");
	public static final Keyword DATA_RESPONSE=Keyword.intern("DR");

}
