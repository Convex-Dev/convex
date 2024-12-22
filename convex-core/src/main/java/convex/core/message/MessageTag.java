package convex.core.message;

import convex.core.data.Keyword;

/**
 * Constant tags used to identify general purpose messages
 */
public class MessageTag {

	public static final Keyword STATUS_REQUEST = Keyword.intern("SR");
	public static final Keyword QUERY=Keyword.intern("Q");
	public static final Keyword TRANSACT=Keyword.intern("TX");
	
	public static final Keyword DATA_REQUEST=Keyword.intern("DR");

	public static final Keyword BYE=Keyword.intern("BYE");

	
}
