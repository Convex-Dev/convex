package convex.db.calcite;

import java.math.BigInteger;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.Constants;

/**
 * Calcite type system for Convex DB, installed on every connection.
 *
 * <p>CVM integers are arbitrary precision, so {@code DECIMAL} precision reaches the
 * largest CVM integer rather than Calcite's default of 19 digits. Result types follow
 * PostgreSQL: {@code SUM} widens so that it cannot overflow, and {@code AVG} over the
 * exact types is {@code NUMERIC}.
 */
public class ConvexTypeSystem extends RelDataTypeSystemImpl {

	public static final ConvexTypeSystem INSTANCE = new ConvexTypeSystem();

	/** Decimal digits of the largest CVM integer, {@link Constants#MAX_BIG_INTEGER_LENGTH} bytes. */
	public static final int MAX_DECIMAL_PRECISION =
		BigInteger.ONE.shiftLeft(8 * Constants.MAX_BIG_INTEGER_LENGTH - 1).toString().length();

	@Override
	public int getMaxPrecision(SqlTypeName typeName) {
		if (typeName == SqlTypeName.DECIMAL) return MAX_DECIMAL_PRECISION;
		return super.getMaxPrecision(typeName);
	}

	/** A {@code DECIMAL} declared without precision has Calcite's default precision, which DDL records. */
	@Override
	public int getDefaultPrecision(SqlTypeName typeName) {
		if (typeName == SqlTypeName.DECIMAL) return RelDataTypeSystem.DEFAULT.getDefaultPrecision(typeName);
		return super.getDefaultPrecision(typeName);
	}

	/** {@code SUM} over the small integers is {@code BIGINT}, and over {@code BIGINT} and {@code NUMERIC} a {@code NUMERIC}. */
	@Override
	public RelDataType deriveSumType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
		RelDataType sumType = switch (argumentType.getSqlTypeName()) {
			case TINYINT, SMALLINT, INTEGER -> typeFactory.createSqlType(SqlTypeName.BIGINT);
			case BIGINT -> numeric(typeFactory, 0);
			case DECIMAL -> numeric(typeFactory, argumentType.getScale());
			default -> null;
		};
		if (sumType == null) return super.deriveSumType(typeFactory, argumentType);
		return typeFactory.createTypeWithNullability(sumType, argumentType.isNullable());
	}

	/** {@code AVG} over the approximate types is {@code DOUBLE PRECISION}, and over the exact types a {@code NUMERIC}. */
	@Override
	public RelDataType deriveAvgAggType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
		RelDataType avgType = switch (argumentType.getSqlTypeName()) {
			case REAL, FLOAT, DOUBLE -> typeFactory.createSqlType(SqlTypeName.DOUBLE);
			case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL -> numeric(typeFactory, getMaxScale(SqlTypeName.DECIMAL));
			default -> null;
		};
		if (avgType == null) return super.deriveAvgAggType(typeFactory, argumentType);
		return typeFactory.createTypeWithNullability(avgType, argumentType.isNullable());
	}

	/** {@code NUMERIC} of the maximum precision and the given scale. */
	private static RelDataType numeric(RelDataTypeFactory typeFactory, int scale) {
		return typeFactory.createSqlType(SqlTypeName.DECIMAL, MAX_DECIMAL_PRECISION, scale);
	}
}
