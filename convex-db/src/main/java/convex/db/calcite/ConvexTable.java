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
import convex.core.data.Vectors;
import convex.db.calcite.convention.ConvexConvention;
import convex.db.calcite.rel.ConvexTableScan;
import convex.db.lattice.SQLSchema;
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
	 * Returns a ConvexTableScan in CONVEX convention. This is Calcite's
	 * standard extension point for custom adapters — bypasses EnumerableTableScan
	 * entirely.
	 */
	@Override
	public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
		RelOptCluster cluster = context.getCluster();
		// TODO: declare PK collation on trait set once ConvexConvention
		// operators properly propagate collation traits through the plan.
		// Currently the planner elides sorts at the Enumerable boundary.
		RelTraitSet traitSet = cluster.traitSetOf(ConvexConvention.INSTANCE);
		return new ConvexTableScan(cluster, traitSet, relOptTable);
	}

	public ConvexColumnType[] getColumnTypes() {
		return schema.getTables().getColumnTypes(tableName);
	}

	/**
	 * Returns table statistics for the Calcite cost-based optimiser.
	 *
	 * <p>Provides:
	 * <ul>
	 *   <li>Row count from the Index (O(1), may include tombstones)
	 *   <li>Primary key declaration (column 0 is unique)
	 *   <li>Sort order (rows are sorted by PK via the radix tree Index)
	 * </ul>
	 */
	@Override
	public Statistic getStatistic() {
		// Row count: O(1) from Index.count()
		// TODO: track exact live row count to exclude tombstones after deletes
		Double rowCount = null;
		SQLTable table = schema.getTables().getLiveTable(tableName);
		if (table != null) {
			var rows = table.getRows();
			rowCount = (rows != null) ? (double) rows.count() : 0.0;
		}

		// PK is column 0
		List<ImmutableBitSet> keys = List.of(ImmutableBitSet.of(0));

		// TODO: declare PK collation once trait propagation is implemented
		// TODO: add column cardinality for selectivity estimation
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

	public long executeUpdate(Enumerable<Object[]> input, int columnCount, int[] updateIndices) {
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
			for (Object[] row : input) {
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

				if (pkBeingUpdated && !oldPk.equals(newPk)) {
					if (schema.getTables().selectByKey(tableName, newPk) != null) {
						throw new RuntimeException("Unique constraint violation: primary key '" +
							newPk + "' already exists in table '" + tableName + "'");
					}
				}

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

	private ACell toCell(Object v, int columnIndex) {
		ConvexColumnType[] types = getColumnTypes();
		ConvexColumnType type = (types != null && columnIndex < types.length) ? types[columnIndex] : ConvexColumnType.of(ConvexType.ANY);
		return type.toCell(v);
	}
}
