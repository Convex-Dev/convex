package convex.db.calcite;

import java.util.Collection;
import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Calcite Table backed by Convex lattice storage.
 *
 * <p>Implements both ScannableTable (for SELECT queries) and ModifiableTable
 * (for INSERT, UPDATE, DELETE operations).
 *
 * <p>All values in the lattice are dynamically typed, so this table maps
 * them to Calcite's ANY type for maximum flexibility.
 */
public class ConvexTable extends AbstractQueryableTable
		implements ScannableTable, ModifiableTable {

	private final ConvexSchema schema;
	private final String tableName;

	/**
	 * Creates a new ConvexTable.
	 *
	 * @param schema Parent schema
	 * @param tableName Table name
	 */
	public ConvexTable(ConvexSchema schema, String tableName) {
		super(Object[].class);
		this.schema = schema;
		this.tableName = tableName;
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		RelDataTypeFactory.Builder builder = typeFactory.builder();

		String[] columnNames = schema.getTables().getColumnNames(tableName);
		if (columnNames != null) {
			for (String colName : columnNames) {
				// Use ANY type since lattice values are dynamically typed
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
				return new ConvexEnumerator(schema.getTables(), tableName);
			}
		};
	}

	@Override
	public Collection<Object[]> getModifiableCollection() {
		// Not used - we use direct methods instead
		throw new UnsupportedOperationException("Use executeInsert/Update/Delete instead");
	}

	@Override
	public TableModify toModificationRel(
			RelOptCluster cluster,
			RelOptTable table,
			Prepare.CatalogReader catalogReader,
			RelNode child,
			TableModify.Operation operation,
			List<String> updateColumnList,
			List<RexNode> sourceExpressionList,
			boolean flattened) {
		// Return a logical modify node - ConvexTableModifyRule will convert
		// it to the physical ConvexTableModify in ENUMERABLE convention
		return ConvexLogicalTableModify.create(
			table,
			catalogReader,
			child,
			operation,
			updateColumnList,
			sourceExpressionList,
			flattened);
	}

	@Override
	public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
		// Delegate to scan-based enumeration
		@SuppressWarnings("unchecked")
		Queryable<T> queryable = (Queryable<T>) Linq4j.asEnumerable(
			scan(null).toList()).asQueryable();
		return queryable;
	}

	/**
	 * Gets the table name.
	 *
	 * @return Table name
	 */
	public String getTableName() {
		return tableName;
	}

	/**
	 * Gets the parent schema.
	 *
	 * @return ConvexSchema
	 */
	public ConvexSchema getSchema() {
		return schema;
	}

	// ========== DML Operations (called from generated code) ==========

	/**
	 * Execute INSERT - called from ConvexTableModify generated code.
	 */
	public long executeInsert(Enumerable<Object[]> input) {
		long count = 0;
		for (Object[] row : input) {
			if (row != null && insertRow(row)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Execute UPDATE - called from ConvexTableModify generated code.
	 */
	public long executeUpdate(Enumerable<Object[]> input, int columnCount, int[] updateIndices) {
		long count = 0;
		for (Object[] row : input) {
			if (row == null) continue;

			// Reconstruct updated row from Calcite's format:
			// [original columns..., new values for updated columns...]
			Object[] updatedRow = new Object[columnCount];
			for (int i = 0; i < columnCount; i++) {
				updatedRow[i] = row[i];
			}
			for (int i = 0; i < updateIndices.length; i++) {
				int targetIdx = updateIndices[i];
				if (targetIdx >= 0 && targetIdx < columnCount) {
					updatedRow[targetIdx] = row[columnCount + i];
				}
			}

			// Delete old row by PK, insert updated row
			ACell pk = toCell(updatedRow[0]);
			schema.getTables().deleteByKey(tableName, pk);
			if (insertRow(updatedRow)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Execute DELETE - called from ConvexTableModify generated code.
	 */
	public long executeDelete(Enumerable<Object[]> input) {
		long count = 0;
		for (Object[] row : input) {
			if (row != null && row.length > 0) {
				ACell pk = toCell(row[0]);
				if (schema.getTables().deleteByKey(tableName, pk)) {
					count++;
				}
			}
		}
		return count;
	}

	private boolean insertRow(Object[] row) {
		if (row == null || row.length < 1) return false;
		ACell[] cells = new ACell[row.length];
		for (int i = 0; i < row.length; i++) {
			cells[i] = toCell(row[i]);
		}
		return schema.getTables().insert(tableName, Vectors.of(cells));
	}

	private ACell toCell(Object v) {
		if (v == null) return null;
		if (v instanceof ACell c) return c;
		if (v instanceof byte[] b) return Blob.wrap(b);
		if (v instanceof Long l) return CVMLong.create(l);
		if (v instanceof Integer i) return CVMLong.create(i);
		if (v instanceof Double d) return CVMDouble.create(d);
		if (v instanceof Float f) return CVMDouble.create(f);
		if (v instanceof Number n) return CVMLong.create(n.longValue()); // Handle BigDecimal, etc.
		if (v instanceof String s) return Strings.create(s);
		return Strings.create(v.toString());
	}
}
