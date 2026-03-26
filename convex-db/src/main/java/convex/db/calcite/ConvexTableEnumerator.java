package convex.db.calcite;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;

/**
 * Enumerator that performs a full table scan from a lattice Index.
 *
 * <p>Rows are returned in Index key order (sorted by PK).
 * Pre-collects via forEach for O(n) traversal instead of O(n*depth) entryAt().
 */
public class ConvexTableEnumerator extends ConvexEnumerator {

	private final List<ACell[]> rows;
	private int position = -1;

	/**
	 * Creates an enumerator for a full table scan.
	 */
	public ConvexTableEnumerator(SQLSchema tables, String tableName) {
		SQLTable table = tables.getLiveTable(tableName);
		if (table == null) {
			this.rows = List.of();
			return;
		}
		Index<ABlob, AVector<ACell>> rawRows = table.getRows();
		if (rawRows == null) {
			this.rows = List.of();
			return;
		}
		// Single-pass tree traversal, skip tombstones, unwrap values
		List<ACell[]> collected = new ArrayList<>((int) Math.min(rawRows.count(), Integer.MAX_VALUE));
		rawRows.forEach((k, v) -> {
			if (SQLRow.isLive(v)) {
				collected.add(SQLRow.getValues(v).toCellArray());
			}
		});
		this.rows = collected;
	}

	@Override
	public boolean moveNext() {
		if (++position < rows.size()) {
			currentRow = rows.get(position);
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
