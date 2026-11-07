package convex.lattice.cursor;

import java.util.NoSuchElementException;

/** A required cursor location has no value. A stored nil is a value, not absence. */
public class MissingValueException extends NoSuchElementException {
	private static final long serialVersionUID = 1L;

	public MissingValueException() {
		super("No value exists at this cursor location");
	}
}
