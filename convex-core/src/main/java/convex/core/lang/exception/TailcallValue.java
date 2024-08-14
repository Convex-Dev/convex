package convex.core.lang.exception;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.lang.AFn;

/**
 * Class representing a function return value.
 * 
 * Contains argument values for each parameter to be substituted in the
 * surrounding function / loop
 */
public class TailcallValue extends ATrampoline {

	private AFn<?> function;

	private TailcallValue(AFn<?> f, ACell[] values) {
		super(values);
		this.function=f;
	}
	
	public static TailcallValue wrap(AFn<?> f, ACell[] args) {
		return new TailcallValue(f,args);
	}

	@Override
	public String toString() {
		AVector<?> seq = Vectors.create(args); // should always convert OK
		return "Tailcall: " + seq;
	}

	@Override
	public ACell getCode() {
		return ErrorCodes.TAILCALL;
	}

	@Override
	public ACell getMessage() {
		return null;
	}

	public AFn<?> getFunction() {
		return function;
	}


}
