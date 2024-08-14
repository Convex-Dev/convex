package convex.core.exceptions;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.StringShort;
import convex.core.lang.exception.AThrowable;

/**
 * Exceptional value representing a condition the CVM should not catch
 */
public class Failure extends AThrowable {

	public StringShort JUICE_MESSAGE=StringShort.create("Out of juice!");
	
	private static final Failure JUICE_FAILURE=new Failure(ErrorCodes.JUICE);
	
	protected Failure(Keyword code) {
		super(code);
		
	}

	@Override
	public ACell getMessage() {
		if (ErrorCodes.JUICE.equals(code)) return JUICE_MESSAGE;
		return null;
	}
	
	@Override
	public boolean isCatchable() {
		return false;
	}
	
	public static Failure juice() {
		return JUICE_FAILURE;
	}

	@Override
	public void addTrace(String traceMessage) {
		// Do nothing, we don't trace non-catchable failures
	}

}
