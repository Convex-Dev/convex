package convex.db.calcite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Tests for ConvexExpressionEvaluator.
 */
public class ConvexExpressionEvaluatorTest {

	private RelDataTypeFactory typeFactory;
	private RexBuilder rexBuilder;

	@BeforeEach
	void setUp() {
		typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
		rexBuilder = new RexBuilder(typeFactory);
	}

	@Test
	void testLiteralInteger() {
		RexNode lit = rexBuilder.makeLiteral(42, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
		ACell result = ConvexExpressionEvaluator.evaluate(lit, new ACell[0], null);

		// Calcite may return BigDecimal for numeric literals
		assertTrue(result instanceof CVMLong || result instanceof CVMDouble,
			"Expected numeric type but got: " + (result != null ? result.getClass().getName() : "null"));
		if (result instanceof CVMLong l) {
			assertEquals(42L, l.longValue());
		} else if (result instanceof CVMDouble d) {
			assertEquals(42.0, d.doubleValue(), 0.001);
		}
	}

	@Test
	void testLiteralDouble() {
		RexNode lit = rexBuilder.makeLiteral(3.14, typeFactory.createSqlType(SqlTypeName.DOUBLE), false);
		ACell result = ConvexExpressionEvaluator.evaluate(lit, new ACell[0], null);

		assertTrue(result instanceof CVMDouble);
		assertEquals(3.14, ((CVMDouble) result).doubleValue(), 0.001);
	}

	@Test
	void testLiteralString() {
		RexNode lit = rexBuilder.makeLiteral("hello");
		ACell result = ConvexExpressionEvaluator.evaluate(lit, new ACell[0], null);

		assertTrue(result instanceof convex.core.data.AString);
		assertEquals("hello", result.toString());
	}

	@Test
	void testLiteralBoolean() {
		RexNode litTrue = rexBuilder.makeLiteral(true);
		RexNode litFalse = rexBuilder.makeLiteral(false);

		ACell resultTrue = ConvexExpressionEvaluator.evaluate(litTrue, new ACell[0], null);
		ACell resultFalse = ConvexExpressionEvaluator.evaluate(litFalse, new ACell[0], null);

		assertEquals(CVMBool.TRUE, resultTrue);
		assertEquals(CVMBool.FALSE, resultFalse);
	}

	@Test
	void testLiteralNull() {
		RexNode lit = rexBuilder.makeNullLiteral(typeFactory.createSqlType(SqlTypeName.INTEGER));
		ACell result = ConvexExpressionEvaluator.evaluate(lit, new ACell[0], null);

		assertNull(result);
	}

	@Test
	void testInputRef() {
		ACell[] row = new ACell[]{
			CVMLong.create(1),
			Strings.create("Alice"),
			CVMLong.create(100)
		};

		RelDataType rowType = typeFactory.builder()
			.add("id", SqlTypeName.INTEGER)
			.add("name", SqlTypeName.VARCHAR)
			.add("amount", SqlTypeName.INTEGER)
			.build();

		RexNode ref0 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
		RexNode ref1 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.VARCHAR), 1);
		RexNode ref2 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 2);

		assertEquals(CVMLong.create(1), ConvexExpressionEvaluator.evaluate(ref0, row, rowType));
		assertEquals(Strings.create("Alice"), ConvexExpressionEvaluator.evaluate(ref1, row, rowType));
		assertEquals(CVMLong.create(100), ConvexExpressionEvaluator.evaluate(ref2, row, rowType));
	}

	@Test
	void testComparisonEquals() {
		ACell[] row = new ACell[]{CVMLong.create(42)};
		RelDataType rowType = typeFactory.builder().add("x", SqlTypeName.INTEGER).build();

		RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
		RexNode lit42 = rexBuilder.makeLiteral(42, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
		RexNode lit99 = rexBuilder.makeLiteral(99, typeFactory.createSqlType(SqlTypeName.INTEGER), false);

		RexNode eq42 = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS, inputRef, lit42);
		RexNode eq99 = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS, inputRef, lit99);

		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(eq42, row, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(eq99, row, rowType));
	}

	@Test
	void testComparisonLessThan() {
		ACell[] row = new ACell[]{CVMLong.create(10)};
		RelDataType rowType = typeFactory.builder().add("x", SqlTypeName.INTEGER).build();

		RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
		RexNode lit20 = rexBuilder.makeLiteral(20, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
		RexNode lit5 = rexBuilder.makeLiteral(5, typeFactory.createSqlType(SqlTypeName.INTEGER), false);

		RexNode lt20 = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.LESS_THAN, inputRef, lit20);
		RexNode lt5 = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.LESS_THAN, inputRef, lit5);

		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(lt20, row, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(lt5, row, rowType));
	}

	@Test
	void testArithmeticAdd() {
		ACell[] row = new ACell[]{CVMLong.create(10), CVMLong.create(5)};
		RelDataType rowType = typeFactory.builder()
			.add("a", SqlTypeName.INTEGER)
			.add("b", SqlTypeName.INTEGER)
			.build();

		RexNode refA = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
		RexNode refB = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 1);
		RexNode sum = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.PLUS, refA, refB);

		ACell result = ConvexExpressionEvaluator.evaluate(sum, row, rowType);
		assertTrue(result instanceof CVMLong);
		assertEquals(15L, ((CVMLong) result).longValue());
	}

	@Test
	void testLogicalAnd() {
		ACell[] row = new ACell[]{CVMLong.create(10)};
		RelDataType rowType = typeFactory.builder().add("x", SqlTypeName.INTEGER).build();

		RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
		RexNode lit5 = rexBuilder.makeLiteral(5, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
		RexNode lit20 = rexBuilder.makeLiteral(20, typeFactory.createSqlType(SqlTypeName.INTEGER), false);

		// x > 5 AND x < 20 (should be true for x=10)
		RexNode gt5 = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.GREATER_THAN, inputRef, lit5);
		RexNode lt20 = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.LESS_THAN, inputRef, lit20);
		RexNode andExpr = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.AND, gt5, lt20);

		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(andExpr, row, rowType));
	}

	@Test
	void testIsNull() {
		ACell[] rowWithNull = new ACell[]{null};
		ACell[] rowWithValue = new ACell[]{CVMLong.create(42)};
		RelDataType rowType = typeFactory.builder().add("x", SqlTypeName.INTEGER).build();

		RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
		RexNode isNull = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.IS_NULL, inputRef);

		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isNull, rowWithNull, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isNull, rowWithValue, rowType));
	}

	/**
	 * The IS TRUE family is three-valued: NULL IS FALSE is false, not the negation of
	 * NULL IS TRUE. Calcite produces IS TRUE itself when it lifts a CASE out of an
	 * aggregate's argument.
	 */
	@Test
	void testIsTrueAndIsFalseFamily() {
		RelDataType rowType = typeFactory.builder().add("x", SqlTypeName.BOOLEAN).build();
		RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.BOOLEAN), 0);

		RexNode isTrue = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.IS_TRUE, inputRef);
		RexNode isNotTrue = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.IS_NOT_TRUE, inputRef);
		RexNode isFalse = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.IS_FALSE, inputRef);
		RexNode isNotFalse = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.IS_NOT_FALSE, inputRef);

		ACell[] rowTrue = new ACell[]{CVMBool.TRUE};
		ACell[] rowFalse = new ACell[]{CVMBool.FALSE};
		ACell[] rowNull = new ACell[]{null};

		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isTrue, rowTrue, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isTrue, rowFalse, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isTrue, rowNull, rowType));

		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isNotTrue, rowTrue, rowType));
		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isNotTrue, rowFalse, rowType));
		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isNotTrue, rowNull, rowType));

		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isFalse, rowTrue, rowType));
		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isFalse, rowFalse, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isFalse, rowNull, rowType));

		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isNotFalse, rowTrue, rowType));
		assertEquals(CVMBool.FALSE, ConvexExpressionEvaluator.evaluate(isNotFalse, rowFalse, rowType));
		assertEquals(CVMBool.TRUE, ConvexExpressionEvaluator.evaluate(isNotFalse, rowNull, rowType));
	}

	/** ROUND rounds halves away from zero, keeps integers exact and leaves doubles as doubles. */
	@Test
	void testRound() {
		RelDataType rowType = typeFactory.builder()
			.add("d", SqlTypeName.DOUBLE).add("i", SqlTypeName.BIGINT).add("n", SqlTypeName.INTEGER).build();
		RexNode d = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.DOUBLE), 0);
		RexNode i = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.BIGINT), 1);
		RexNode n = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 2);
		RexNode roundD = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.ROUND, d);
		RexNode roundDN = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.ROUND, d, n);
		RexNode roundIN = rexBuilder.makeCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.ROUND, i, n);

		ACell[] row = {CVMDouble.create(2.345), CVMLong.create(1250), CVMLong.create(2)};
		assertEquals(CVMDouble.create(2.0), ConvexExpressionEvaluator.evaluate(roundD, row, rowType));
		assertEquals(CVMDouble.create(2.35), ConvexExpressionEvaluator.evaluate(roundDN, row, rowType));
		assertEquals(CVMLong.create(1250), ConvexExpressionEvaluator.evaluate(roundIN, row, rowType));

		ACell[] half = {CVMDouble.create(-2.5), CVMLong.create(1250), CVMLong.create(-2)};
		assertEquals(CVMDouble.create(-3.0), ConvexExpressionEvaluator.evaluate(roundD, half, rowType));
		assertEquals(CVMDouble.create(0.0), ConvexExpressionEvaluator.evaluate(roundDN, half, rowType));
		assertEquals(CVMLong.create(1300), ConvexExpressionEvaluator.evaluate(roundIN, half, rowType));

		ACell[] nulls = {null, null, CVMLong.create(1)};
		assertNull(ConvexExpressionEvaluator.evaluate(roundD, nulls, rowType));
		assertNull(ConvexExpressionEvaluator.evaluate(roundIN, nulls, rowType));
	}
}
