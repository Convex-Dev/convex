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
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.FilterableTable;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.db.calcite.eval.ConvexExpressionEvaluator;
import convex.db.lattice.SQLSchema;

/**
 * Calcite Table backed by Convex lattice storage.
 *
 * <p>Implements FilterableTable (for SELECT with filter pushdown) and
 * ModifiableTable (for INSERT, UPDATE, DELETE operations).
 *
 * <p>Primary key equality filters ({@code WHERE id = ?}) are pushed down
 * to the Index radix tree for O(log n) point lookups instead of full scans.
 */
public class ConvexTable extends AbstractQueryableTable
		implements ScannableTable, FilterableTable, ModifiableTable {

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
		scanCount++;
		return new AbstractEnumerable<Object[]>() {
			@Override
			public Enumerator<Object[]> enumerator() {
				return new ConvexEnumerator(schema.getTables(), tableName);
			}
		};
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root, List<RexNode> filters) {
		scanCount++;

		// Try primary key pushdown: WHERE column[0] = literal or = ?param
		ACell pkValue = extractPrimaryKeyEquality(filters, root);
		if (pkValue != null) {
			// Point lookup via Index — O(log n)
			AVector<ACell> row = schema.getTables().selectByKey(tableName, pkValue);
			if (row == null) {
				return Linq4j.emptyEnumerable();
			}
			// Convert AVector to Object[] using column types
			ConvexColumnType[] types = getColumnTypes();
			Object[] javaRow = new Object[(int) row.count()];
			for (int i = 0; i < javaRow.length; i++) {
				ConvexColumnType type = (types != null && i < types.length)
						? types[i] : ConvexColumnType.of(ConvexType.ANY);
				javaRow[i] = type.toJava(row.get(i));
			}
			return Linq4j.singletonEnumerable(javaRow);
		}

		// Full scan fallback
		return new AbstractEnumerable<Object[]>() {
			@Override
			public Enumerator<Object[]> enumerator() {
				return new ConvexEnumerator(schema.getTables(), tableName);
			}
		};
	}

	/**
	 * Extracts a primary key equality value from the filter list.
	 * Looks for {@code column[0] = literal} or {@code column[0] = ?param}
	 * and removes it from the list (telling Calcite we've handled it).
	 *
	 * @param filters Mutable list of filters from Calcite
	 * @param root DataContext for resolving dynamic parameters
	 * @return The PK value as ACell, or null if no PK equality found
	 */
	private static ACell extractPrimaryKeyEquality(List<RexNode> filters, DataContext root) {
		if (filters == null) return null;
		for (int i = 0; i < filters.size(); i++) {
			RexNode filter = filters.get(i);
			if (!(filter instanceof RexCall call)) continue;
			if (call.getKind() != SqlKind.EQUALS) continue;

			var operands = call.getOperands();
			if (operands.size() != 2) continue;

			// column[0] = value (literal or bound param only)
			if (operands.get(0) instanceof RexInputRef ref && ref.getIndex() == 0) {
				ACell val = resolveValue(operands.get(1), root);
				if (val != null) {
					// Don't remove filter — let Calcite re-verify for safety with joins
					return val;
				}
			}
			// value = column[0]
			if (operands.get(1) instanceof RexInputRef ref && ref.getIndex() == 0) {
				ACell val = resolveValue(operands.get(0), root);
				if (val != null) {
					return val;
				}
			}
		}
		return null;
	}

	/**
	 * Resolves a RexNode to an ACell value. Handles literals and dynamic params.
	 */
	private static ACell resolveValue(RexNode node, DataContext root) {
		if (node instanceof RexLiteral lit) {
			return ConvexExpressionEvaluator.literalToCell(lit);
		}
		if (node instanceof RexDynamicParam param && root != null) {
			// Dynamic params are stored as "?0", "?1", etc. in DataContext
			Object val = root.get("?" + param.getIndex());
			if (val != null) {
				return ConvexType.ANY.toCell(val);
			}
		}
		return null;
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
			scan(null, new java.util.ArrayList<>()).toList()).asQueryable();
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
