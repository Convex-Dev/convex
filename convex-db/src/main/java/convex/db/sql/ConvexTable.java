package convex.db.sql;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.db.lattice.LatticeTables;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLTable;

/**
 * Calcite Table implementation backed by a Convex lattice table.
 *
 * <p>Implements ScannableTable for full table scans. Future versions may
 * implement FilterableTable or ProjectableFilterableTable for pushdown.
 */
public class ConvexTable extends AbstractTable implements ScannableTable {

	private final LatticeTables tables;
	private final String tableName;

	public ConvexTable(LatticeTables tables, String tableName) {
		this.tables = tables;
		this.tableName = tableName;
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		RelDataTypeFactory.Builder builder = typeFactory.builder();

		// Add primary key column
		builder.add("_key", SqlTypeName.VARBINARY);

		// Add columns from schema
		AVector<AVector<ACell>> schema = tables.getSchema(tableName);
		if (schema != null) {
			for (int i = 0; i < schema.count(); i++) {
				AVector<ACell> col = schema.get(i);
				String colName = col.get(0).toString();
				// All columns are ANY type for now (mapped to VARCHAR)
				builder.add(colName, SqlTypeName.ANY).nullable(true);
			}
		}

		return builder.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		return new AbstractEnumerable<Object[]>() {
			@Override
			public Enumerator<Object[]> enumerator() {
				return new TableEnumerator();
			}
		};
	}

	/**
	 * Enumerator over table rows.
	 */
	private class TableEnumerator implements Enumerator<Object[]> {
		private final List<Object[]> rows;
		private int index = -1;

		TableEnumerator() {
			this.rows = loadRows();
		}

		private List<Object[]> loadRows() {
			List<Object[]> result = new ArrayList<>();

			AVector<ACell> table = getTableState();
			if (table == null || !SQLTable.isLive(table)) {
				return result;
			}

			Index<ABlob, AVector<ACell>> rowIndex = SQLTable.getRows(table);
			if (rowIndex == null) {
				return result;
			}

			AVector<AVector<ACell>> schema = SQLTable.getSchema(table);
			int columnCount = schema != null ? (int) schema.count() : 0;

			for (var entry : rowIndex.entrySet()) {
				AVector<ACell> rowEntry = entry.getValue();
				if (!SQLRow.isLive(rowEntry)) continue;

				AVector<ACell> values = SQLRow.getValues(rowEntry);
				Object[] row = new Object[columnCount + 1]; // +1 for _key

				// First column is the primary key
				row[0] = entry.getKey().getBytes();

				// Remaining columns are values
				if (values != null) {
					for (int i = 0; i < columnCount && i < values.count(); i++) {
						row[i + 1] = convertValue(values.get(i));
					}
				}

				result.add(row);
			}

			return result;
		}

		private AVector<ACell> getTableState() {
			Index<AString, AVector<ACell>> store = tables.cursor().get();
			if (store == null) return null;
			return store.get(convex.core.data.Strings.create(tableName));
		}

		private Object convertValue(ACell cell) {
			if (cell == null) return null;
			if (cell instanceof CVMLong l) return l.longValue();
			if (cell instanceof CVMDouble d) return d.doubleValue();
			if (cell instanceof AString s) return s.toString();
			if (cell instanceof ABlob b) return b.getBytes();
			return cell.toString();
		}

		@Override
		public Object[] current() {
			return rows.get(index);
		}

		@Override
		public boolean moveNext() {
			if (index < rows.size() - 1) {
				index++;
				return true;
			}
			return false;
		}

		@Override
		public void reset() {
			index = -1;
		}

		@Override
		public void close() {
			// Nothing to close
		}
	}
}
