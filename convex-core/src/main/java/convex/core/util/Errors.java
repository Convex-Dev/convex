package convex.core.util;

import convex.core.ErrorCodes;
import convex.core.data.ARecord;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.lang.exception.ErrorValue;

/**
 * Utility class for generating appropriate error messages
 * 
 * "I keep a list of all unresolved bugs I've seen on the forum.  In some cases, 
 * I'm still thinking about the best design for the fix.  This isn't the kind of 
 * software where we can leave so many unresolved bugs that we need a tracker for them."
 * 
 * – Satoshi Nakamoto
 */
public class Errors {



	public static String immutable(Object a) {
		return "Object is immutable: "+a.getClass();
	}

	public static String sizeOutOfRange(long i) {
		return "Index out of range: "+i;
	}

	public static String illegalPosition(long position) {
		return "Illegal index position: "+position;
	}

	public static String insufficientFunds(Address source, long amount) {
		return "Insufficient funds in account ["+source+"] required="+amount;
	}

	public static String unknownKey(Keyword key, ARecord record) {
		return "Unknown key ["+key+"] for record type: "+record.getClass();
	}

	public static String badIndex(long i) {
		return "Bad index: "+i;
	}

	public static String badRange(long start, long end) {
		return "Range out of bounds: ["+start+", "+end+")";
	}

	public static String negativeLength(long length) {
		return "Negative length: "+length;
	}

	public static String wrongLength(long expected, long count) {
		return "Wrong length, expected="+expected+" and actual="+count;
	}

	public static ErrorValue nobody(Address address) {
		return ErrorValue.create(ErrorCodes.NOBODY,"Account does not exist: "+address);
	}
	
	public static ErrorValue INVALID_NUMERIC = ErrorValue.create(ErrorCodes.ARGUMENT,"Invalid numeric result");

}
