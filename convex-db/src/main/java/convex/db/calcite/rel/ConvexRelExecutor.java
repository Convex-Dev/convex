package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>Uses a registry to store ConvexRel references that can be looked up
 * by generated code at runtime.
 *
 * <p>Type conversion follows SQL/JDBC conventions:
 * <ul>
 *   <li>CVMLong → Long</li>
 *   <li>CVMDouble → Double</li>
 *   <li>CVMBool → Boolean</li>
 *   <li>AString → String</li>
 *   <li>AInteger (big) → java.math.BigInteger</li>
 *   <li>ABlob → byte[]</li>
 *   <li>null → null</li>
 *   <li>Other → toString()</li>
 * </ul>
 */
public class ConvexRelExecutor {

	private static final AtomicLong ID_GENERATOR = new AtomicLong();
	private static final Map<Long, ConvexRel> REGISTRY = new ConcurrentHashMap<>();

	/**
	 * Registers a ConvexRel for later execution.
	 *
	 * @param convexRel The ConvexRel to register
	 * @return ID that can be used to retrieve and execute the rel
	 */
	public static long register(ConvexRel convexRel) {
		long id = ID_GENERATOR.incrementAndGet();
		REGISTRY.put(id, convexRel);
		return id;
	}

	/**
	 * Executes a registered ConvexRel and returns results as Enumerable&lt;Object[]&gt;.
	 *
	 * <p>This method is called from Calcite-generated code.
	 *
	 * @param id The registered ConvexRel ID
	 * @param fieldCount Number of fields in output rows
	 * @return Enumerable of Object[] rows with Java types
	 */
	public static Enumerable<Object[]> execute(long id, int fieldCount) {
		ConvexRel convexRel = REGISTRY.remove(id);
		if (convexRel == null) {
			throw new IllegalStateException("ConvexRel not found for id: " + id);
		}

		// Execute the ConvexRel tree
		ConvexEnumerable convexResult = convexRel.execute();

		// Convert ACell[] to Object[]
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
	 * Converts a CVM cell to a Java object for JDBC consumption.
	 *
	 * <p>Uses RT.jvm for standard conversions, with special handling for blobs.
	 *
	 * @param cell CVM cell
	 * @return Java object
	 */
	public static Object cellToJava(ACell cell) {
		if (cell == null) {
			return null;
		}

		// Blob → byte[] (special case not handled by RT.jvm)
		if (cell instanceof ABlob blob) {
			return blob.getBytes();
		}

		// Use RT.jvm for standard CVM → JVM conversions
		// (handles Long, Double, Boolean, String, BigInteger for big values, etc.)
		return RT.jvm(cell);
	}
}
