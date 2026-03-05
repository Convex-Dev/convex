package convex.db.calcite;

import java.util.Iterator;

import org.apache.calcite.linq4j.Enumerator;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.db.lattice.SQLSchema;

/**
 * Enumerator for iterating over rows in a Convex lattice table.
 *
 * <p>Converts CVM cell values to Java objects for Calcite consumption,
 * using the declared column types for proper type conversion.
 */
public class ConvexEnumerator implements Enumerator<Object[]> {

	private final Iterator<AVector<ACell>> rowIterator;
	private final int columnCount;
	private final ConvexColumnType[] columnTypes;
	private AVector<ACell> currentRow;

	/**
	 * Creates a new enumerator for the given table.
	 *
	 * @param tables LatticeTables instance
	 * @param tableName Table to enumerate
	 */
	public ConvexEnumerator(SQLSchema tables, String tableName) {
		var rows = tables.selectAll(tableName);
		// Convert Index values to iterator
		if (rows != null) {
			java.util.List<AVector<ACell>> rowList = new java.util.ArrayList<>();
			for (var entry : rows.entrySet()) {
				rowList.add(entry.getValue());
			}
			this.rowIterator = rowList.iterator();
		} else {
			this.rowIterator = java.util.Collections.emptyIterator();
		}

		String[] columns = tables.getColumnNames(tableName);
		this.columnCount = (columns != null) ? columns.length : 0;
		this.columnTypes = tables.getColumnTypes(tableName);
	}

	@Override
	public Object[] current() {
		if (currentRow == null) {
			return null;
		}

		Object[] result = new Object[columnCount];
		for (int i = 0; i < columnCount && i < currentRow.count(); i++) {
			ConvexColumnType type = (columnTypes != null && i < columnTypes.length) ? columnTypes[i] : ConvexColumnType.of(ConvexType.ANY);
			result[i] = type.toJava(currentRow.get(i));
		}
		return result;
	}

	@Override
	public boolean moveNext() {
		if (rowIterator.hasNext()) {
			currentRow = rowIterator.next();
			return true;
		}
		currentRow = null;
		return false;
	}

	@Override
	public void reset() {
		throw new UnsupportedOperationException("Reset not supported");
	}

	@Override
	public void close() {
		// Nothing to close
	}
}
