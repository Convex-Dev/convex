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

import convex.core.data.ACell;
import convex.core.data.Vectors;
import convex.db.lattice.SQLSchema;

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

		SQLSchema tables = schema.getTables();
		String[] columnNames = tables.getColumnNames(tableName);
		ConvexColumnType[] columnTypes = tables.getColumnTypes(tableName);

		if (columnNames != null && columnTypes != null) {
			for (int i = 0; i < columnNames.length; i++) {
				RelDataType type = columnTypes[i].toRelDataType(typeFactory);
				builder.add(columnNames[i], typeFactory.createTypeWithNullability(type, true));
			}
		}

		return builder.build();
	}

	/**
	 * Gets the column types for this table.
	 *
	 * @return Array of ConvexColumnType for each column
	 */
	public ConvexColumnType[] getColumnTypes() {
		return schema.getTables().getColumnTypes(tableName);
	}

	/** Scan counter for diagnostics — reset via {@link #resetScanCount()} */
	private static volatile int scanCount = 0;
	public static int getScanCount() { return scanCount; }
	public static void resetScanCount() { scanCount = 0; }

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		scanCount++; // TODO: remove after benchmarking complete
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
		try {
			long count = 0;
			for (Object[] row : input) {
				if (row != null && insertRow(row)) {
					count++;
				}
			}
			return count;
		} catch (ExceptionInInitializerError e) {
			throw wrapTypeError(e, "INSERT");
		}
	}

	/**
	 * Execute UPDATE - called from ConvexTableModify generated code.
	 *
	 * <p>Follows PostgreSQL semantics: PK updates are allowed but uniqueness
	 * is enforced. If the new PK already exists (and is different from the
	 * current row's PK), throws a unique constraint violation.
	 *
	 * <p>Type validation is performed on updated columns.
	 */
	public long executeUpdate(Enumerable<Object[]> input, int columnCount, int[] updateIndices) {
		try {
			// Check if PK column (index 0) is being updated
			boolean pkBeingUpdated = false;
			for (int idx : updateIndices) {
				if (idx == 0) {
					pkBeingUpdated = true;
					break;
				}
			}

			ConvexColumnType[] types = getColumnTypes();

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
						// Validate type before assignment
						Object newValue = row[columnCount + i];
						ConvexColumnType type = (types != null && targetIdx < types.length) ? types[targetIdx] : ConvexColumnType.of(ConvexType.ANY);
						type.toCell(newValue); // Validates type, throws if invalid
						updatedRow[targetIdx] = newValue;
					}
				}

				ACell oldPk = toCell(row[0], 0);
				ACell newPk = toCell(updatedRow[0], 0);

				// If PK is being changed, check for uniqueness violation
				if (pkBeingUpdated && !oldPk.equals(newPk)) {
					// Check if new PK already exists
					if (schema.getTables().selectByKey(tableName, newPk) != null) {
						throw new RuntimeException("Unique constraint violation: primary key '" +
							newPk + "' already exists in table '" + tableName + "'");
					}
				}

				// Delete old row by ORIGINAL PK, then insert with new values
				schema.getTables().deleteByKey(tableName, oldPk);
				if (insertRow(updatedRow)) {
					count++;
				}
			}
			return count;
		} catch (ExceptionInInitializerError e) {
			throw wrapTypeError(e, "UPDATE");
		}
	}

	/**
	 * Execute DELETE - called from ConvexTableModify generated code.
	 */
	public long executeDelete(Enumerable<Object[]> input) {
		try {
			long count = 0;
			for (Object[] row : input) {
				if (row != null && row.length > 0) {
					ACell pk = toCell(row[0], 0);
					if (schema.getTables().deleteByKey(tableName, pk)) {
						count++;
					}
				}
			}
			return count;
		} catch (ExceptionInInitializerError e) {
			throw wrapTypeError(e, "DELETE");
		}
	}

	/**
	 * Wraps type conversion errors from Calcite's generated code into a RuntimeException
	 * with a clear SQL error message. Avatica will convert this to SQLException.
	 */
	private RuntimeException wrapTypeError(ExceptionInInitializerError e, String operation) {
		Throwable cause = e.getCause();
		String message = "Type conversion error in " + operation + " on table '" + tableName + "'";
		if (cause instanceof NumberFormatException nfe) {
			message += ": invalid number format - " + nfe.getMessage();
		} else if (cause != null) {
			message += ": " + cause.getMessage();
		}
		return new RuntimeException(message, e);
	}

	private boolean insertRow(Object[] row) {
		if (row == null || row.length < 1) return false;
		ConvexColumnType[] types = getColumnTypes();
		ACell[] cells = new ACell[row.length];
		for (int i = 0; i < row.length; i++) {
			ConvexColumnType type = (types != null && i < types.length) ? types[i] : ConvexColumnType.of(ConvexType.ANY);
			cells[i] = type.toCell(row[i]);
		}
		return schema.getTables().insert(tableName, Vectors.of(cells));
	}

	/**
	 * Converts a value to a CVM cell using the specified column type.
	 *
	 * @param v Value to convert
	 * @param columnIndex Column index for type lookup
	 * @return CVM cell
	 */
	private ACell toCell(Object v, int columnIndex) {
		ConvexColumnType[] types = getColumnTypes();
		ConvexColumnType type = (types != null && columnIndex < types.length) ? types[columnIndex] : ConvexColumnType.of(ConvexType.ANY);
		return type.toCell(v);
	}

	/**
	 * Converts a value to a CVM cell (untyped - uses ANY).
	 * Use toCell(v, columnIndex) for type-validated conversion.
	 */
	private ACell toCell(Object v) {
		return ConvexType.ANY.toCell(v);
	}
}
