package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;

/**
 * Runtime executor for ConvexRel query plans.
 *
 * <p>Plans are registered at compile time and reused across executions
 * (e.g. PreparedStatement). The DataContext is passed at each execution
 * to resolve dynamic parameters.
 *
 * <p>Two execution methods match Calcite's row format contracts:
 * <ul>
 *   <li>{@link #execute} — ARRAY format, returns {@code Enumerable<Object[]>}</li>
 *   <li>{@link #executeScalar} — SCALAR format, returns {@code Enumerable<Object>}
 *       where each element is the column value directly</li>
 * </ul>
 */
public class ConvexRelExecutor {

	private static final AtomicLong ID_GENERATOR = new AtomicLong();
	private static final Map<Long, ConvexRel> REGISTRY = new ConcurrentHashMap<>();

	/**
	 * Registers a ConvexRel for execution. The plan remains registered
	 * for reuse by subsequent executions (PreparedStatement reuse).
	 */
	public static long register(ConvexRel convexRel) {
		long id = ID_GENERATOR.incrementAndGet();
		REGISTRY.put(id, convexRel);
		return id;
	}

	/**
	 * Executes in ARRAY format — each row is {@code Object[]}.
	 * Used for multi-column results.
	 */
	public static Enumerable<Object[]> execute(long id, int fieldCount, DataContext ctx) {
		ConvexEnumerable convexResult = executeRel(id, ctx);

		List<Object[]> results = new ArrayList<>();
		for (ACell[] row : convexResult) {
			Object[] javaRow = new Object[fieldCount];
			for (int i = 0; i < Math.min(row.length, fieldCount); i++) {
				javaRow[i] = cellToJava(row[i]);
			}
			results.add(javaRow);
		}
		return Linq4j.asEnumerable(results);
	}

	/**
	 * Executes in SCALAR format — each element is the column value directly.
	 * Used for single-column results.
	 */
	public static Enumerable<Object> executeScalar(long id, DataContext ctx) {
		ConvexEnumerable convexResult = executeRel(id, ctx);

		List<Object> results = new ArrayList<>();
		for (ACell[] row : convexResult) {
			results.add(row.length > 0 ? cellToJava(row[0]) : null);
		}
		return Linq4j.asEnumerable(results);
	}

	private static ConvexEnumerable executeRel(long id, DataContext ctx) {
		ConvexRel convexRel = REGISTRY.get(id);
		if (convexRel == null) {
			throw new IllegalStateException("ConvexRel not found for id: " + id);
		}
		return convexRel.execute(ctx);
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
