package convex.db.calcite.convention;

import org.apache.calcite.DataContext;
import org.apache.calcite.rel.RelNode;

/**
 * Relational expression that operates on ACell[] rows.
 *
 * <p>All physical operators in the CONVEX convention implement this interface.
 * The execute method returns rows as ACell arrays, keeping CVM types throughout
 * the execution pipeline.
 */
public interface ConvexRel extends RelNode {

	/**
	 * Executes this relational expression.
	 *
	 * @param ctx DataContext for resolving dynamic parameters (may be null)
	 * @return ConvexEnumerable yielding ACell[] rows
	 */
	ConvexEnumerable execute(DataContext ctx);
}
