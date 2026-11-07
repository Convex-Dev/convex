package convex.db.calcite.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;

import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.db.jdbc.SQLExceptions;

/** Exercises the CVM evaluator independently of Calcite's choice of physical plan. */
class ExpressionDataExceptionTest {

	private final SqlTypeFactoryImpl types = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
	private final RexBuilder rex = new RexBuilder(types);

	private ACell binary(SqlOperator op, SqlTypeName type, ACell a, ACell b) {
		RexNode left = rex.makeInputRef(types.createSqlType(type), 0);
		RexNode right = rex.makeInputRef(types.createSqlType(type), 1);
		return ConvexExpressionEvaluator.evaluate(rex.makeCall(types.createSqlType(type), op, List.of(left, right)),
			new ACell[] {a, b}, null);
	}

	private ACell cast(ACell value, SqlTypeName source, SqlTypeName target) {
		RexNode ref = rex.makeInputRef(types.createSqlType(source), 0);
		RexNode call = rex.makeCall(types.createSqlType(target), SqlStdOperatorTable.CAST, List.of(ref));
		return ConvexExpressionEvaluator.evaluate(call, new ACell[] {value}, null);
	}

	@Test
	void divisionAndModuloByZero() {
		for (SqlOperator op : List.of(SqlStdOperatorTable.DIVIDE, SqlStdOperatorTable.MOD)) {
			for (SqlTypeName type : List.of(SqlTypeName.BIGINT, SqlTypeName.DOUBLE)) {
				RuntimeException error = assertThrows(RuntimeException.class,
					() -> binary(op, type, CVMLong.ONE, type == SqlTypeName.BIGINT ? CVMLong.ZERO : CVMDouble.create(-0.0)));
				assertEquals("22012", SQLExceptions.translate(error).getSQLState());
				assertNull(binary(op, type, null, CVMLong.ZERO));
				assertNull(binary(op, type, CVMLong.ONE, null));
			}
		}
	}

	@Test
	void invalidTextCasts() {
		for (SqlTypeName type : List.of(SqlTypeName.INTEGER, SqlTypeName.BIGINT, SqlTypeName.DOUBLE)) {
			RuntimeException error = assertThrows(RuntimeException.class, () -> cast(Strings.create("uno"), SqlTypeName.VARCHAR, type));
			assertEquals("22P02", SQLExceptions.translate(error).getSQLState());
			assertInstanceOf(SQLException.class, error.getCause());
			assertInstanceOf(NumberFormatException.class, error.getCause().getCause());
			assertNull(cast(null, SqlTypeName.VARCHAR, type));
		}
	}

	@Test
	void integerCastOverflowIsNotInvalidText() {
		for (String value : List.of("9223372036854775808", "-9223372036854775809")) {
			RuntimeException error = assertThrows(RuntimeException.class, () -> cast(Strings.create(value), SqlTypeName.VARCHAR, SqlTypeName.BIGINT));
			assertEquals("22003", SQLExceptions.translate(error).getSQLState());
		}
		RuntimeException error = assertThrows(RuntimeException.class,
			() -> cast(CVMLong.create(2147483648L), SqlTypeName.BIGINT, SqlTypeName.INTEGER));
		assertEquals("22003", SQLExceptions.translate(error).getSQLState());
	}

	@Test
	void arithmeticOverflow() {
		RuntimeException error = assertThrows(RuntimeException.class,
			() -> binary(SqlStdOperatorTable.PLUS, SqlTypeName.BIGINT, CVMLong.MAX_VALUE, CVMLong.ONE));
		assertEquals("22003", SQLExceptions.translate(error).getSQLState());
		error = assertThrows(RuntimeException.class,
			() -> binary(SqlStdOperatorTable.DIVIDE, SqlTypeName.BIGINT, CVMLong.MIN_VALUE, CVMLong.MINUS_ONE));
		assertEquals("22003", SQLExceptions.translate(error).getSQLState());
	}

	@Test
	void validCastsAndBoundaries() {
		assertEquals(CVMLong.MAX_VALUE, cast(Strings.create("9223372036854775807"), SqlTypeName.VARCHAR, SqlTypeName.BIGINT));
		assertEquals(CVMLong.MIN_VALUE, cast(Strings.create("-9223372036854775808"), SqlTypeName.VARCHAR, SqlTypeName.BIGINT));
		assertEquals(CVMLong.ONE, cast(CVMBigInteger.wrap(BigInteger.ONE), SqlTypeName.DECIMAL, SqlTypeName.INTEGER));
		assertEquals(CVMDouble.create(1.5), cast(Strings.create("1.5"), SqlTypeName.VARCHAR, SqlTypeName.DOUBLE));
		assertEquals(CVMLong.create(3), binary(SqlStdOperatorTable.DIVIDE, SqlTypeName.BIGINT, CVMLong.create(7), CVMLong.create(2)));
	}
}
