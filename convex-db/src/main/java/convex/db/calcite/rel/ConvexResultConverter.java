package convex.db.calcite.rel;

import java.util.Iterator;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;

/**
 * Converts ConvexRel results (ACell[]) to Java types for JDBC consumption.
 *
 * <p>Called from Calcite-generated code at the ConvexConvention → Enumerable
 * boundary. The ConvexRel tree is passed via {@code implementor.stash()}.
 *
 * <p>Two methods match Calcite's row format contracts:
 * <ul>
 *   <li>{@link #execute} — ARRAY format, returns {@code Enumerable<Object[]>}</li>
 *   <li>{@link #executeScalar} — SCALAR format, returns {@code Enumerable<Object>}</li>
 * </ul>
 */
public class ConvexResultConverter {

	/**
	 * Executes in ARRAY format — each row is {@code Object[]}.
	 * Used for multi-column results.
	 *
	 * <p>Returns a lazy {@code Enumerable}: no rows are decoded until Calcite
	 * iterates via {@code rs.next()}, keeping the heap footprint near-zero
	 * at query-open time.
	 */
	public static Enumerable<Object[]> execute(ConvexRel rel, int fieldCount, DataContext ctx) {
		ConvexEnumerable convexResult = rel.execute(ctx);
		return Linq4j.asEnumerable(() -> {
			Iterator<ACell[]> it = convexResult.iterator();
			return new Iterator<Object[]>() {
				@Override public boolean hasNext() { return it.hasNext(); }
				@Override public Object[] next() {
					ACell[] row = it.next();
					Object[] javaRow = new Object[fieldCount];
					for (int i = 0; i < Math.min(row.length, fieldCount); i++) {
						javaRow[i] = cellToJava(row[i]);
					}
					return javaRow;
				}
			};
		});
	}

	/**
	 * Executes in SCALAR format — each element is the column value directly.
	 * Used for single-column results.
	 *
	 * <p>Returns a lazy {@code Enumerable}: no rows are decoded until iteration.
	 */
	public static Enumerable<Object> executeScalar(ConvexRel rel, DataContext ctx) {
		ConvexEnumerable convexResult = rel.execute(ctx);
		return Linq4j.asEnumerable(() -> {
			Iterator<ACell[]> it = convexResult.iterator();
			return new Iterator<Object>() {
				@Override public boolean hasNext() { return it.hasNext(); }
				@Override public Object next() {
					ACell[] row = it.next();
					return row.length > 0 ? cellToJava(row[0]) : null;
				}
			};
		});
	}

	/**
	 * Converts a CVM cell to a Java object for JDBC consumption.
	 */
	public static Object cellToJava(ACell cell) {
		if (cell == null) return null;
		if (cell instanceof ABlob blob) return blob.getBytes();
		return RT.jvm(cell);
	}
}
