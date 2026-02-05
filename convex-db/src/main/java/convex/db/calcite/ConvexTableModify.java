package convex.db.calcite;

import java.util.List;

import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rex.RexNode;

/**
 * Relational expression for modifying a ConvexTable.
 *
 * <p>Generates code that calls ConvexTable's insert/update/delete methods
 * directly, bypassing the Collection abstraction.
 */
public class ConvexTableModify extends TableModify implements EnumerableRel {

	public ConvexTableModify(
			RelOptCluster cluster,
			RelTraitSet traitSet,
			RelOptTable table,
			Prepare.CatalogReader catalogReader,
			RelNode input,
			Operation operation,
			List<String> updateColumnList,
			List<RexNode> sourceExpressionList,
			boolean flattened) {
		super(cluster, traitSet, table, catalogReader, input, operation,
			updateColumnList, sourceExpressionList, flattened);
	}

	@Override
	public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
		return new ConvexTableModify(
			getCluster(), traitSet, getTable(), getCatalogReader(),
			sole(inputs), getOperation(), getUpdateColumnList(),
			getSourceExpressionList(), isFlattened());
	}

	@Override
	public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
		final BlockBuilder builder = new BlockBuilder();
		final Result inputResult = implementor.visitChild(this, 0, (EnumerableRel) getInput(), pref);

		Expression inputExp = builder.append("input", inputResult.block);

		// Cast input to Enumerable for method resolution
		Expression enumerableInput = Expressions.convert_(inputExp, Enumerable.class);

		final PhysType physType = PhysTypeImpl.of(
			implementor.getTypeFactory(),
			getRowType(),
			pref.preferCustom());

		// Get the ConvexTable via registry lookup
		// Table qualified name is [schemaName, tableName]
		java.util.List<String> qualifiedName = getTable().getQualifiedName();
		String schemaName = qualifiedName.get(0);
		String tableName = qualifiedName.size() > 1 ? qualifiedName.get(1) : qualifiedName.get(0);

		final Expression tableExp = Expressions.call(
			ConvexSchemaFactory.class,
			"getTable",
			Expressions.constant(schemaName),
			Expressions.constant(tableName));

		// Generate code based on operation
		final Expression countExp;
		switch (getOperation()) {
			case INSERT:
				countExp = Expressions.call(
					tableExp,
					"executeInsert",
					enumerableInput);
				break;
			case UPDATE:
				int colCount = getTable().getRowType().getFieldCount();
				int[] updateIndices = getUpdateColumnIndices();
				countExp = Expressions.call(
					tableExp,
					"executeUpdate",
					enumerableInput,
					Expressions.constant(colCount),
					Expressions.constant(updateIndices));
				break;
			case DELETE:
				countExp = Expressions.call(
					tableExp,
					"executeDelete",
					enumerableInput);
				break;
			default:
				throw new UnsupportedOperationException("Operation not supported: " + getOperation());
		}

		builder.add(
			Expressions.return_(null,
				Expressions.call(
					org.apache.calcite.util.BuiltInMethod.SINGLETON_ENUMERABLE.method,
					countExp)));

		return implementor.result(physType, builder.toBlock());
	}

	private int[] getUpdateColumnIndices() {
		List<String> updateCols = getUpdateColumnList();
		if (updateCols == null || updateCols.isEmpty()) {
			return new int[0];
		}
		List<String> fieldNames = getTable().getRowType().getFieldNames();
		int[] indices = new int[updateCols.size()];
		for (int i = 0; i < updateCols.size(); i++) {
			String updateCol = updateCols.get(i);
			int idx = -1;
			for (int j = 0; j < fieldNames.size(); j++) {
				if (fieldNames.get(j).equalsIgnoreCase(updateCol)) {
					idx = j;
					break;
				}
			}
			indices[i] = idx;
		}
		return indices;
	}
}
