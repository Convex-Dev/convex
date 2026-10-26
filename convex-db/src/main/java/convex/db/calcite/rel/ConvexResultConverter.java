package convex.db.calcite.rel;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.convention.ConvexEnumerable;
import convex.db.calcite.convention.ConvexRel;

/**
 * Converts ConvexRel results (ACell[]) to Java types for JDBC consumption.
 *
 * <p>Called from Calcite-generated code at the ConvexConvention → Enumerable
 * boundary. The ConvexRel tree is passed via {@code implementor.stash()}.
 * Each value is converted to the Java value Calcite expects for its result
 * column's type, by {@link ConvexColumnType#toJava(ACell, SqlTypeName)}.
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
	 */
	public static Enumerable<Object[]> execute(ConvexRel rel, int fieldCount, DataContext ctx) {
		SqlTypeName[] types = fieldTypes(rel);
		ConvexEnumerable convexResult = rel.execute(ctx);

		List<Object[]> results = new ArrayList<>();
		for (ACell[] row : convexResult) {
			Object[] javaRow = new Object[fieldCount];
			for (int i = 0; i < Math.min(row.length, fieldCount); i++) {
				javaRow[i] = ConvexColumnType.toJava(row[i], types[i]);
			}
			results.add(javaRow);
		}
		return Linq4j.asEnumerable(results);
	}

	/**
	 * Executes in SCALAR format — each element is the column value directly.
	 * Used for single-column results.
	 */
	public static Enumerable<Object> executeScalar(ConvexRel rel, DataContext ctx) {
		SqlTypeName type = fieldTypes(rel)[0];
		ConvexEnumerable convexResult = rel.execute(ctx);

		List<Object> results = new ArrayList<>();
		for (ACell[] row : convexResult) {
			results.add(row.length > 0 ? ConvexColumnType.toJava(row[0], type) : null);
		}
		return Linq4j.asEnumerable(results);
	}

	/** The Calcite type of each result column, which decides its Java values. */
	private static SqlTypeName[] fieldTypes(ConvexRel rel) {
		List<RelDataTypeField> fields = rel.getRowType().getFieldList();
		SqlTypeName[] types = new SqlTypeName[fields.size()];
		for (int i = 0; i < types.length; i++) {
			types[i] = fields.get(i).getType().getSqlTypeName();
		}
		return types;
	}

	/**
	 * Converts a CVM cell to a Java object for JDBC consumption, by the cell's own type.
	 */
	public static Object cellToJava(ACell cell) {
		if (cell == null) return null;
		if (cell instanceof ABlob blob) return blob.getBytes();
		return RT.jvm(cell);
	}
}
