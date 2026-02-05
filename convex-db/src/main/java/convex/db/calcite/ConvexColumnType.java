package convex.db.calcite;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ACell;

/**
 * Represents a SQL column type with optional precision and scale.
 *
 * <p>Wraps a ConvexType (base SQL type) with parameters for types like
 * VARCHAR(100), CHAR(3), DECIMAL(10,2), TIMESTAMP(6), etc.
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

	// ========== Accessors ==========

	/** Gets the base SQL type. */
	public ConvexType getBaseType() {
		return baseType;
	}

	/** Gets the Calcite SqlTypeName. */
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

	/** Converts a CVM cell to a Java value for JDBC. */
	public Object toJava(ACell cell) {
		return baseType.toJava(cell);
	}

	// ========== Calcite Integration ==========

	/**
	 * Creates the Calcite RelDataType for this column type.
	 *
	 * @param typeFactory The type factory to use
	 * @return RelDataType with appropriate precision/scale
	 */
	public RelDataType toRelDataType(RelDataTypeFactory typeFactory) {
		SqlTypeName sqlType = getSqlTypeName();

		if (hasScale()) {
			return typeFactory.createSqlType(sqlType, precision, scale);
		} else if (hasPrecision()) {
			return typeFactory.createSqlType(sqlType, precision);
		} else {
			return typeFactory.createSqlType(sqlType);
		}
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
