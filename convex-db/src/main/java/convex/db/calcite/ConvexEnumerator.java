package convex.db.calcite;

import java.util.Iterator;

import org.apache.calcite.linq4j.Enumerator;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.LatticeTables;

/**
 * Enumerator for iterating over rows in a Convex lattice table.
 *
 * <p>Converts CVM cell values to Java objects for Calcite consumption.
 */
public class ConvexEnumerator implements Enumerator<Object[]> {

	private final Iterator<AVector<ACell>> rowIterator;
	private final int columnCount;
	private AVector<ACell> currentRow;

	/**
	 * Creates a new enumerator for the given table.
	 *
	 * @param tables LatticeTables instance
	 * @param tableName Table to enumerate
	 */
	public ConvexEnumerator(LatticeTables tables, String tableName) {
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
	}

	@Override
	public Object[] current() {
		if (currentRow == null) {
			return null;
		}

		Object[] result = new Object[columnCount];
		for (int i = 0; i < columnCount && i < currentRow.count(); i++) {
			result[i] = toJava(currentRow.get(i));
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

	/**
	 * Converts a CVM cell to a Java object.
	 *
	 * @param cell CVM cell value
	 * @return Java object suitable for Calcite
	 */
	private Object toJava(ACell cell) {
		if (cell == null) return null;

		if (cell instanceof CVMLong l) {
			long value = l.longValue();
			// Return Integer for values that fit, to match SQL literal types
			// (SQL literals like "1" are treated as Integer by Calcite)
			if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
				return (int) value;
			}
			return value;
		}
		if (cell instanceof CVMDouble d) {
			return d.doubleValue();
		}
		if (cell instanceof AString s) {
			return s.toString();
		}
		if (cell instanceof ABlob b) {
			return b.getBytes();
		}

		// Default: convert to string representation
		return cell.toString();
	}
}
