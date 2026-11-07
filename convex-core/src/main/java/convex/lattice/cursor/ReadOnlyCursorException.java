package convex.lattice.cursor;

/** A write was attempted through a read-only cursor or view. Nothing was written. */
public class ReadOnlyCursorException extends UnsupportedOperationException {
	private static final long serialVersionUID = 1L;

	public ReadOnlyCursorException() {
		super("Cursor is read-only");
	}
}
