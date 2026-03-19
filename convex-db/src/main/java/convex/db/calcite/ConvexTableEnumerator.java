package convex.db.calcite;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.db.lattice.SQLSchema;

/**
 * Enumerator that performs a full table scan from a lattice Index.
 *
 * <p>Rows are returned in Index key order (sorted by PK).
 * No materialisation — holds the immutable Index and advances by position.
 */
public class ConvexTableEnumerator extends ConvexEnumerator {

	private final Index<ABlob, AVector<ACell>> index;
	private final long count;
	private long position = -1;

	/**
	 * Creates an enumerator for a full table scan.
	 */
	public ConvexTableEnumerator(SQLSchema tables, String tableName) {
		this.index = tables.selectAll(tableName);
		this.count = (index != null) ? index.count() : 0;
	}

	@Override
	public boolean moveNext() {
		if (++position < count) {
			currentRow = index.entryAt(position).getValue().toCellArray();
			return true;
		}
		currentRow = null;
		return false;
	}

	@Override
	public void reset() {
		position = -1;
		currentRow = null;
	}
}
