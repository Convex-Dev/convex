package convex.db.calcite;

import java.sql.Timestamp;
import java.util.Map;

import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Defines the mapping between SQL types and Convex CVM types.
 *
 * <p>Each ConvexType represents a column type that can be used in a ConvexTable.
 * The ANY type allows dynamic typing (any CVM value).
 */
public enum ConvexType {

	/** 64-bit signed integer. SQL: BIGINT, Convex: CVMLong */
	BIGINT(SqlTypeName.BIGINT, CVMLong.class),

	/** 32-bit signed integer. SQL: INTEGER, Convex: CVMLong */
	INTEGER(SqlTypeName.INTEGER, CVMLong.class),

	/** Double-precision floating point. SQL: DOUBLE, Convex: CVMDouble */
	DOUBLE(SqlTypeName.DOUBLE, CVMDouble.class),

	/** Variable-length string. SQL: VARCHAR, Convex: AString */
	VARCHAR(SqlTypeName.VARCHAR, AString.class),

	/** Boolean. SQL: BOOLEAN, Convex: CVMBool */
	BOOLEAN(SqlTypeName.BOOLEAN, CVMBool.class),

	/** Variable-length binary data. SQL: VARBINARY, Convex: ABlob */
	VARBINARY(SqlTypeName.VARBINARY, ABlob.class),

	/** Binary large object. SQL: BLOB, Convex: ABlob */
	BLOB(SqlTypeName.BINARY, ABlob.class),

	/** Timestamp (milliseconds since epoch). SQL: TIMESTAMP, Convex: CVMLong */
	TIMESTAMP(SqlTypeName.TIMESTAMP, CVMLong.class),

	/** JSON/Map data. SQL: OTHER (JSON), Convex: AMap */
	JSON(SqlTypeName.OTHER, AMap.class),

	/** Dynamic type - accepts any CVM value. SQL: ANY, Convex: ACell */
	ANY(SqlTypeName.ANY, ACell.class);

	private final SqlTypeName sqlType;
	private final Class<? extends ACell> cvmType;

	ConvexType(SqlTypeName sqlType, Class<? extends ACell> cvmType) {
		this.sqlType = sqlType;
		this.cvmType = cvmType;
	}

	/** Gets the Calcite SQL type name. */
	public SqlTypeName getSqlType() {
		return sqlType;
	}

	/** Gets the expected CVM type class. */
	public Class<? extends ACell> getCvmType() {
		return cvmType;
	}

	/**
	 * Converts a Java/SQL value to a CVM cell, validating the type.
	 *
	 * @param value The value to convert
	 * @return The CVM cell
	 * @throws IllegalArgumentException if the value cannot be converted to this type
	 */
	public ACell toCell(Object value) {
		if (value == null) return null;
		if (value instanceof ACell cell) {
			if (this == ANY || cvmType.isInstance(cell)) {
				return cell;
			}
			throw new IllegalArgumentException("Expected " + this + " but got " + cell.getClass().getSimpleName());
		}

		return switch (this) {
			case BIGINT, INTEGER -> {
				if (value instanceof Number n) yield CVMLong.create(n.longValue());
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case DOUBLE -> {
				if (value instanceof Number n) yield CVMDouble.create(n.doubleValue());
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case VARCHAR -> {
				if (value instanceof String s) yield Strings.create(s);
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case BOOLEAN -> {
				if (value instanceof Boolean b) yield CVMBool.create(b);
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case VARBINARY, BLOB -> {
				if (value instanceof byte[] b) yield Blob.wrap(b);
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case TIMESTAMP -> {
				if (value instanceof Number n) yield CVMLong.create(n.longValue());
				if (value instanceof Timestamp ts) yield CVMLong.create(ts.getTime());
				if (value instanceof java.util.Date d) yield CVMLong.create(d.getTime());
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case JSON -> {
				if (value instanceof Map<?,?> m) yield mapToAMap(m);
				if (value instanceof String s) yield parseJsonString(s);
				throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to " + this);
			}
			case ANY -> convertAny(value);
		};
	}

	/**
	 * Converts a CVM cell to a Java value for JDBC.
	 *
	 * @param cell The CVM cell
	 * @return The Java value
	 */
	public Object toJava(ACell cell) {
		if (cell == null) return null;

		return switch (this) {
			case BIGINT -> cell instanceof CVMLong l ? l.longValue() : null;
			case INTEGER -> cell instanceof CVMLong l ? (int) l.longValue() : null;
			case DOUBLE -> cell instanceof CVMDouble d ? d.doubleValue() : null;
			case VARCHAR -> cell instanceof AString s ? s.toString() : null;
			case BOOLEAN -> cell instanceof CVMBool b ? b.booleanValue() : null;
			case VARBINARY, BLOB -> cell instanceof ABlob b ? b.getBytes() : null;
			case TIMESTAMP -> cell instanceof CVMLong l ? new Timestamp(l.longValue()) : null;
			case JSON -> cell instanceof AMap<?,?> m ? m.toString() : null;
			case ANY -> convertAnyToJava(cell);
		};
	}

	/** Converts any Java value to a CVM cell (for ANY type). */
	private static ACell convertAny(Object value) {
		if (value == null) return null;
		if (value instanceof ACell c) return c;
		if (value instanceof Long l) return CVMLong.create(l);
		if (value instanceof Integer i) return CVMLong.create(i);
		if (value instanceof Number n) return CVMLong.create(n.longValue());
		if (value instanceof Double d) return CVMDouble.create(d);
		if (value instanceof Float f) return CVMDouble.create(f);
		if (value instanceof String s) return Strings.create(s);
		if (value instanceof Boolean b) return CVMBool.create(b);
		if (value instanceof byte[] b) return Blob.wrap(b);
		if (value instanceof Map<?,?> m) return mapToAMap(m);
		if (value instanceof Timestamp ts) return CVMLong.create(ts.getTime());
		return Strings.create(value.toString());
	}

	/** Converts a Java Map to an AMap. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static AMap<ACell, ACell> mapToAMap(Map<?,?> map) {
		AMap result = Maps.empty();
		for (Map.Entry<?,?> entry : map.entrySet()) {
			ACell key = convertAny(entry.getKey());
			ACell val = convertAny(entry.getValue());
			result = result.assoc(key, val);
		}
		return result;
	}

	/** Parses a JSON string to an AMap (basic implementation). */
	private static ACell parseJsonString(String json) {
		// For now, store as string - full JSON parsing would need a library
		return Strings.create(json);
	}

	/** Converts any CVM cell to a Java value (for ANY type). */
	private static Object convertAnyToJava(ACell cell) {
		if (cell == null) return null;
		if (cell instanceof CVMLong l) {
			long v = l.longValue();
			// Return Integer for small values to match SQL literal types
			if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
				return (int) v;
			}
			return v;
		}
		if (cell instanceof CVMDouble d) return d.doubleValue();
		if (cell instanceof AString s) return s.toString();
		if (cell instanceof CVMBool b) return b.booleanValue();
		if (cell instanceof ABlob b) return b.getBytes();
		if (cell instanceof AMap<?,?> m) return m.toString();
		return cell.toString();
	}

	/**
	 * Parses a type name string to a ConvexType.
	 *
	 * @param name Type name (case-insensitive)
	 * @return The ConvexType
	 * @throws IllegalArgumentException if unknown type
	 */
	public static ConvexType fromName(String name) {
		return switch (name.toUpperCase()) {
			case "BIGINT", "LONG" -> BIGINT;
			case "INTEGER", "INT" -> INTEGER;
			case "DOUBLE", "FLOAT", "REAL" -> DOUBLE;
			case "VARCHAR", "STRING", "TEXT", "CHAR" -> VARCHAR;
			case "BOOLEAN", "BOOL" -> BOOLEAN;
			case "VARBINARY", "BYTES" -> VARBINARY;
			case "BINARY", "BLOB" -> BLOB;
			case "TIMESTAMP", "DATETIME" -> TIMESTAMP;
			case "JSON", "MAP", "OBJECT" -> JSON;
			case "ANY", "VARIANT", "DYNAMIC" -> ANY;
			default -> throw new IllegalArgumentException("Unknown type: " + name);
		};
	}
}
