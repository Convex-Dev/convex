package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AVector;

/**
 * Thrown when a write that must add new rows meets a primary key the table already
 * holds as a live row, or one repeated among the rows written. Nothing is written.
 */
public class DuplicateKeyException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	private final transient AVector<ACell> row;

	/**
	 * @param row The row whose key is taken, its key first
	 */
	public DuplicateKeyException(AVector<ACell> row) {
		super("Key (" + row.get(0) + ") already exists");
		this.row = row;
	}

	/** The row whose key is taken, its key first. */
	public AVector<ACell> getRow() {
		return row;
	}
}
