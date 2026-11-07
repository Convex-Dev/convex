package convex.db.calcite;

/**
 * Raised when a query plan would read or write a table under a binding it was not built
 * for: the table has been dropped, or its schema or format version has changed, since the
 * plan was made (CAD039). The statement must be prepared again.
 */
public class StalePlanException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String tableName;

	public StalePlanException(String tableName) {
		super("Table '" + tableName + "' was dropped or changed after the statement was prepared");
		this.tableName = tableName;
	}

	/** The table that changed. */
	public String getTableName() {
		return tableName;
	}
}
