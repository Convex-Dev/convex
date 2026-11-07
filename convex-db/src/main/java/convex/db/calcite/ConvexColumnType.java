package convex.db.calcite;

import java.math.BigDecimal;
import java.sql.SQLException;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Represents a SQL column type with optional precision and scale.
 *
 * <p>Wraps a ConvexType (base SQL type) with parameters for types like
 * VARCHAR(100), CHAR(3), DECIMAL(10,2), TIMESTAMP(6), etc.
 *
 * <p>A column read from a table is presented to Calcite as the type its stored
 * values actually have in the table's format version (see {@link #toRelDataType}),
 * and its values are converted to the Java values Calcite expects for that type
 * (see {@link #toJava(ACell, SqlTypeName)}).
 */
public class ConvexColumnType {

	private final ConvexType baseType;
	private final int precision;  // -1 means default/unspecified
	private final int scale;      // -1 means default/unspecified

	private ConvexColumnType(ConvexType baseType, int precision, int scale) {
		this.baseType = baseType;
		this.precision = precision;
		this.scale = scale;
	}

	// ========== Factory Methods ==========

	/** Creates a column type with no parameters. */
	public static ConvexColumnType of(ConvexType type) {
		return new ConvexColumnType(type, -1, -1);
	}

	/** Creates a column type with precision only (VARCHAR, CHAR, BINARY, etc). */
	public static ConvexColumnType withPrecision(ConvexType type, int precision) {
		return new ConvexColumnType(type, precision, -1);
	}

	/** Creates a column type with precision and scale (DECIMAL, NUMERIC). */
	public static ConvexColumnType withScale(ConvexType type, int precision, int scale) {
		return new ConvexColumnType(type, precision, scale);
	}

	// ========== Convenience Factory Methods ==========

	/** VARCHAR(length) */
	public static ConvexColumnType varchar(int length) {
		return withPrecision(ConvexType.VARCHAR, length);
	}

	/** CHAR(length) */
	public static ConvexColumnType character(int length) {
		return withPrecision(ConvexType.CHAR, length);
	}

	/** DECIMAL(precision, scale) */
	public static ConvexColumnType decimal(int precision, int scale) {
		return withScale(ConvexType.DECIMAL, precision, scale);
	}

	/** BINARY(length) */
	public static ConvexColumnType binary(int length) {
		return withPrecision(ConvexType.BLOB, length);
	}

	/** VARBINARY(length) */
	public static ConvexColumnType varbinary(int length) {
		return withPrecision(ConvexType.VARBINARY, length);
	}

	/** TIMESTAMP(precision) - fractional seconds precision */
	public static ConvexColumnType timestamp(int precision) {
		return withPrecision(ConvexType.TIMESTAMP, precision);
	}

	/**
	 * Creates the column type of a stored column definition
	 * {@code [name, typeName, precision, scale]}, where the type name is null for
	 * ANY and precision and scale may be absent.
	 *
	 * @param column Column definition from a table's schema
	 * @param version Format version of the table
	 * @return Column type
	 * @throws IllegalArgumentException if the version is not 0, the only version
	 *         whose presentation is defined here
	 */
	public static ConvexColumnType fromSchema(AVector<ACell> column, int version) {
		if (version != 0) {
			throw new IllegalArgumentException("Unsupported table format version " + version);
		}
		ACell typeCell = column.get(1);
		ConvexType baseType = (typeCell == null) ? ConvexType.ANY : ConvexType.fromName(typeCell.toString());

		int precision = -1;
		int scale = -1;
		if (column.count() > 2 && column.get(2) instanceof CVMLong p) {
			precision = (int) p.longValue();
		}
		if (column.count() > 3 && column.get(3) instanceof CVMLong s) {
			scale = (int) s.longValue();
		}

		if (scale >= 0) return withScale(baseType, precision, scale);
		if (precision >= 0) return withPrecision(baseType, precision);
		return of(baseType);
	}

	// ========== Accessors ==========

	/** Gets the base SQL type. */
	public ConvexType getBaseType() {
		return baseType;
	}

	/** Gets the Calcite SqlTypeName the column is presented as. */
	public SqlTypeName getSqlTypeName() {
		return baseType.getSqlType();
	}

	/** Gets precision, or -1 if not specified. */
	public int getPrecision() {
		return precision;
	}

	/** Gets scale, or -1 if not specified. */
	public int getScale() {
		return scale;
	}

	/** Returns true if precision is specified. */
	public boolean hasPrecision() {
		return precision >= 0;
	}

	/** Returns true if scale is specified. */
	public boolean hasScale() {
		return scale >= 0;
	}

	/** Gets the expected CVM type class. */
	public Class<? extends ACell> getCvmType() {
		return baseType.getCvmType();
	}

	// ========== Type Conversion ==========

	/** Converts a Java/SQL value to a CVM cell, validating the type. */
	public ACell toCell(Object value) {
		return baseType.toCell(value);
	}

	/** Converts a stored CVM cell to the Java value Calcite expects for this column's type. */
	public Object toJava(ACell cell) {
		return toJava(cell, getSqlTypeName());
	}

	/**
	 * Converts a CVM cell to the Java value Calcite expects in a column of the given type.
	 * Integers keep their exact value: a {@code BigDecimal} for {@code DECIMAL}, and the
	 * boxed type of an integer type when the value is in its range. Other cells convert as
	 * {@link RT#jvm}, with blobs as byte arrays.
	 *
	 * @param cell CVM cell, or null
	 * @param type Calcite type of the column
	 * @return Java value, or null for null
	 * @throws IllegalArgumentException with an {@link SQLException} of SQLSTATE 22003 as its
	 *         cause, if an integer is outside the range of an integer type
	 */
	public static Object toJava(ACell cell, SqlTypeName type) {
		if (cell == null) return null;
		if (cell instanceof AInteger i) {
			switch (type) {
				case DECIMAL: return new BigDecimal(i.big());
				case BIGINT: return checkLong(i, Long.MIN_VALUE, Long.MAX_VALUE, "bigint");
				case INTEGER: return (int) checkLong(i, Integer.MIN_VALUE, Integer.MAX_VALUE, "integer");
				case SMALLINT: return (short) checkLong(i, Short.MIN_VALUE, Short.MAX_VALUE, "smallint");
				case TINYINT: return (byte) checkLong(i, Byte.MIN_VALUE, Byte.MAX_VALUE, "tinyint");
				case DOUBLE, FLOAT: return i.doubleValue();
				case REAL: return (float) i.doubleValue();
				default: break;
			}
		}
		if (cell instanceof CVMDouble d && type == SqlTypeName.DECIMAL && Double.isFinite(d.doubleValue())) {
			return BigDecimal.valueOf(d.doubleValue());
		}
		if (cell instanceof ABlob b) return b.getBytes();
		return RT.jvm(cell);
	}

	private static long checkLong(AInteger i, long min, long max, String typeName) {
		if (i.isLong()) {
			long v = i.longValue();
			if (v >= min && v <= max) return v;
		}
		String message = typeName + " out of range: " + i;
		throw new IllegalArgumentException(message, new SQLException(message, "22003"));
	}

	// ========== Calcite Integration ==========

	/**
	 * Creates the Calcite RelDataType this column is presented as. A version 0
	 * {@code BIGINT} holds any CVM integer, so it is presented as {@code NUMERIC}
	 * with scale 0, of the maximum precision.
	 *
	 * @param typeFactory The type factory to use
	 * @return RelDataType with appropriate precision/scale
	 */
	public RelDataType toRelDataType(RelDataTypeFactory typeFactory) {
		SqlTypeName sqlType = getSqlTypeName();

		if (baseType == ConvexType.BIGINT) {
			return typeFactory.createSqlType(sqlType, ConvexTypeSystem.MAX_DECIMAL_PRECISION, 0);
		} else if (hasScale()) {
			return typeFactory.createSqlType(sqlType, precision, scale);
		} else if (hasPrecision()) {
			return typeFactory.createSqlType(sqlType, precision);
		} else {
			return typeFactory.createSqlType(sqlType);
		}
	}

	// ========== Calcite Conversion ==========

	/**
	 * Creates a ConvexColumnType from a Calcite RelDataType.
	 *
	 * @param relType Calcite type from DDL parsing
	 * @return ConvexColumnType
	 */
	public static ConvexColumnType fromRelDataType(RelDataType relType) {
		ConvexType base = ConvexType.fromSqlTypeName(relType.getSqlTypeName());

		// Only carry precision/scale for types that actually support it
		SqlTypeName sqlType = relType.getSqlTypeName();
		boolean typeAllowsPrecision = sqlType.allowsPrec();
		// Integer/boolean types don't use precision even if Calcite reports one
		if (sqlType == SqlTypeName.BIGINT || sqlType == SqlTypeName.INTEGER
				|| sqlType == SqlTypeName.SMALLINT || sqlType == SqlTypeName.TINYINT
				|| sqlType == SqlTypeName.BOOLEAN || sqlType == SqlTypeName.DOUBLE
				|| sqlType == SqlTypeName.FLOAT || sqlType == SqlTypeName.REAL) {
			return of(base);
		}

		int p = relType.getPrecision();
		int s = relType.getScale();
		boolean hasPrecision = typeAllowsPrecision && p >= 0 && p < 65536;
		boolean hasScale = sqlType.allowsScale() && s > 0;
		if (hasScale && hasPrecision) {
			return withScale(base, p, s);
		} else if (hasPrecision) {
			return withPrecision(base, p);
		}
		return of(base);
	}

	// ========== Parsing ==========

	/**
	 * Parses a SQL type string like "VARCHAR(100)" or "DECIMAL(10,2)".
	 *
	 * @param typeStr SQL type string
	 * @return ConvexColumnType
	 * @throws IllegalArgumentException if invalid format
	 */
	public static ConvexColumnType parse(String typeStr) {
		if (typeStr == null || typeStr.isBlank()) {
			throw new IllegalArgumentException("Type string cannot be empty");
		}

		String upper = typeStr.trim().toUpperCase();
		int parenStart = upper.indexOf('(');

		if (parenStart < 0) {
			// No parameters: "INTEGER", "VARCHAR", etc.
			return of(ConvexType.fromName(upper));
		}

		int parenEnd = upper.indexOf(')');
		if (parenEnd < 0 || parenEnd < parenStart) {
			throw new IllegalArgumentException("Invalid type format: " + typeStr);
		}

		String baseName = upper.substring(0, parenStart).trim();
		String params = upper.substring(parenStart + 1, parenEnd).trim();
		ConvexType baseType = ConvexType.fromName(baseName);

		if (params.isEmpty()) {
			return of(baseType);
		}

		String[] parts = params.split(",");
		if (parts.length == 1) {
			int precision = Integer.parseInt(parts[0].trim());
			return withPrecision(baseType, precision);
		} else if (parts.length == 2) {
			int precision = Integer.parseInt(parts[0].trim());
			int scale = Integer.parseInt(parts[1].trim());
			return withScale(baseType, precision, scale);
		} else {
			throw new IllegalArgumentException("Invalid type parameters: " + typeStr);
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(baseType.name());
		if (hasScale()) {
			sb.append('(').append(precision).append(',').append(scale).append(')');
		} else if (hasPrecision()) {
			sb.append('(').append(precision).append(')');
		}
		return sb.toString();
	}
}
