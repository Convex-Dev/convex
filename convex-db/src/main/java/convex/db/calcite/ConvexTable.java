package convex.db.calcite;

import java.util.Collection;
import java.util.List;

import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.util.ImmutableBitSet;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.rel.ConvexTableScan;
import convex.db.lattice.SQLTable;

/**
 * Calcite Table backed by Convex lattice storage.
 *
 * <p>Implements TranslatableTable so Calcite uses our ConvexTableScan
 * directly — all queries go through the ConvexConvention pipeline
 * (ConvexFilter, ConvexSort, etc.) rather than Calcite's Enumerable
 * table scan. This ensures PK lookups use the index, not full scans.
 *
 * <p>Implements ModifiableTable for INSERT, UPDATE, DELETE operations.
 */
public class ConvexTable extends AbstractQueryableTable
		implements TranslatableTable, ModifiableTable {

	private final ConvexSchema schema;
	private final String tableName;

	public ConvexTable(ConvexSchema schema, String tableName) {
		super(Object[].class);
		this.schema = schema;
		this.tableName = tableName;
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return rowType(schema.getTables().getBinding(tableName), typeFactory);
	}

	/** The row type of a table with the given binding: no columns if it has none. */
	private static RelDataType rowType(SQLTable.Binding binding, RelDataTypeFactory typeFactory) {
		RelDataTypeFactory.Builder builder = typeFactory.builder();
		if (binding != null) {
			String[] columnNames = binding.columnNames();
			ConvexColumnType[] columnTypes = binding.columnTypes();
			for (int i = 0; i < columnNames.length; i++) {
				RelDataType type = columnTypes[i].toRelDataType(typeFactory);
				builder.add(columnNames[i], typeFactory.createTypeWithNullability(type, true));
			}
		}
		return builder.build();
	}

	/**
	 * Returns a ConvexTableScan in CONVEX convention. This is Calcite's
	 * standard extension point for custom adapters — bypasses EnumerableTableScan
	 * entirely.
	 */
	@Override
	public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
		RelOptCluster cluster = context.getCluster();
		// No PK collation is declared: CONVEX operators do not propagate collation traits (#795)
		RelTraitSet traitSet = cluster.traitSetOf(ConvexConvention.INSTANCE);
		return new ConvexTableScan(cluster, traitSet, relOptTable, bind(relOptTable, cluster.getTypeFactory()));
	}

	// ========== Plan binding (CAD039) ==========

	/**
	 * The binding a plan over this table is built for: its schema and format version now.
	 * The plan's row type was validated a moment earlier, so this checks the two agree.
	 *
	 * @param planned The table as the plan sees it
	 * @param typeFactory Type factory of the plan
	 * @throws StalePlanException if the table changed while the statement was planned
	 */
	public SQLTable.Binding bind(RelOptTable planned, RelDataTypeFactory typeFactory) {
		SQLTable.Binding binding = schema.getTables().getBinding(tableName);
		if (binding == null || !rowType(binding, typeFactory).equals(planned.getRowType())) {
			throw new StalePlanException(tableName);
		}
		return binding;
	}

	/**
	 * The live table, provided it still has the binding a plan over it was built for.
	 * Every read of the table's rows by a plan goes through this: rows read under another
	 * schema or version would be misread, so the plan fails instead.
	 *
	 * @param planned Binding the plan was built for
	 * @throws StalePlanException if the table has been dropped, or its schema or version changed
	 */
	public SQLTable boundTable(SQLTable.Binding planned) {
		SQLTable table = schema.getTables().getLiveTable(tableName);
		if (table == null || !planned.equals(table.getBinding())) {
			throw new StalePlanException(tableName);
		}
		return table;
	}

	/**
	 * This table, provided it still has the binding a plan over it was built for. Called
	 * from the generated code of a plan that writes the table, before any row is written.
	 *
	 * @param planned Binding the plan was built for
	 * @throws StalePlanException if the table has been dropped, or its schema or version changed
	 */
	public ConvexTable bound(SQLTable.Binding planned) {
		boundTable(planned);
		return this;
	}

	public ConvexColumnType[] getColumnTypes() {
		return schema.getTables().getColumnTypes(tableName);
	}

	/**
	 * Returns table statistics for the Calcite cost-based optimiser.
	 *
	 * <p>Provides:
	 * <ul>
	 *   <li>Row count from the table's live count (O(1))
	 *   <li>Primary key declaration (column 0 is unique)
	 * </ul>
	 * Neither the PK sort order nor column cardinality is declared (#795).
	 */
	@Override
	public Statistic getStatistic() {
		// Row count: O(1) from tracked live count
		Double rowCount = null;
		SQLTable table = schema.getTables().getLiveTable(tableName);
		if (table != null) {
			rowCount = (double) table.getRowCount();
		}

		// PK is column 0
		List<ImmutableBitSet> keys = List.of(ImmutableBitSet.of(0));

		return Statistics.of(rowCount, keys);
	}

	@Override
	public Collection<Object[]> getModifiableCollection() {
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
		@SuppressWarnings("unchecked")
		Queryable<T> queryable = (Queryable<T>) Linq4j.emptyEnumerable().asQueryable();
		return queryable;
	}

	public String getTableName() {
		return tableName;
	}

	public ConvexSchema getSchema() {
		return schema;
	}

	// ========== DML Operations (called from generated code) ==========

	/**
	 * Inserts the rows of an INSERT in one atomic write: every value is converted to its
	 * column's type first, so a value that does not fit leaves the table unchanged.
	 */
	public long executeInsert(Enumerable<?> input) {
		try {
			List<AVector<ACell>> rows = new java.util.ArrayList<>();
			for (Object value : input) {
				Object[] row=normaliseRow(value);
				if (row != null && row.length > 0) rows.add(toCells(row));
			}
			return schema.getTables().insertAll(tableName, rows);
		} catch (ExceptionInInitializerError e) {
			throw wrapTypeError(e, "INSERT");
		}
	}

	public long executeUpdate(Enumerable<?> input, int columnCount, int[] updateIndices) {
		try {
			boolean pkBeingUpdated = false;
			for (int idx : updateIndices) {
				if (idx == 0) {
					pkBeingUpdated = true;
					break;
				}
			}

			ConvexColumnType[] types = getColumnTypes();

			long count = 0;
			for (Object value : input) {
				Object[] row=normaliseRow(value);
				if (row == null) continue;

				Object[] updatedRow = new Object[columnCount];
				for (int i = 0; i < columnCount; i++) {
					updatedRow[i] = row[i];
				}
				for (int i = 0; i < updateIndices.length; i++) {
					int targetIdx = updateIndices[i];
					if (targetIdx >= 0 && targetIdx < columnCount) {
						Object newValue = row[columnCount + i];
						ConvexColumnType type = (types != null && targetIdx < types.length) ? types[targetIdx] : ConvexColumnType.of(ConvexType.ANY);
						type.toCell(newValue);
						updatedRow[targetIdx] = newValue;
					}
				}

				ACell oldPk = toCell(row[0], 0);
				ACell newPk = toCell(updatedRow[0], 0);

				// A changed key moves the row: free the old key first, once the new one is known
				// to be free. Otherwise the insert replaces the row under its key in one write;
				// deleting first would publish a tombstone that can tie with, and outlive, the
				// new row on a replica.
				if (pkBeingUpdated && !oldPk.equals(newPk)) {
					if (schema.getTables().selectByKey(tableName, newPk) != null) {
						throw new RuntimeException("Unique constraint violation: primary key '" +
							newPk + "' already exists in table '" + tableName + "'");
					}
					schema.getTables().deleteByKey(tableName, oldPk);
				}
				if (insertRow(updatedRow)) {
					count++;
				}
			}
			return count;
		} catch (ExceptionInInitializerError e) {
			throw wrapTypeError(e, "UPDATE");
		}
	}

	public long executeDelete(Enumerable<?> input) {
		try {
			long count = 0;
			for (Object value : input) {
				Object[] row=normaliseRow(value);
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

	/** Normalises Calcite's scalar representation for single-column rows. */
	private static Object[] normaliseRow(Object value) {
		if (value==null) return null;
		if (value instanceof Object[] row) return row;
		return new Object[] {value};
	}

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
		return schema.getTables().insert(tableName, toCells(row));
	}

	/** A row's values as cells of their columns' types. */
	private AVector<ACell> toCells(Object[] row) {
		ConvexColumnType[] types = getColumnTypes();
		ACell[] cells = new ACell[row.length];
		for (int i = 0; i < row.length; i++) {
			ConvexColumnType type = (types != null && i < types.length) ? types[i] : ConvexColumnType.of(ConvexType.ANY);
			cells[i] = type.toCell(row[i]);
		}
		return Vectors.of(cells);
	}

	private ACell toCell(Object v, int columnIndex) {
		ConvexColumnType[] types = getColumnTypes();
		ConvexColumnType type = (types != null && columnIndex < types.length) ? types[columnIndex] : ConvexColumnType.of(ConvexType.ANY);
		return type.toCell(v);
	}
}
