package convex.db.calcite.eval;

import java.math.BigDecimal;
import java.util.List;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Evaluates RexNode expressions against ACell[] rows using CVM operations.
 *
 * <p>This keeps all values as CVM types throughout evaluation, only using
 * CVM comparison and arithmetic operations.
 */
public class ConvexExpressionEvaluator {

	/**
	 * Evaluates an expression against a row.
	 *
	 * @param expr The expression to evaluate
	 * @param row The input row as ACell[]
	 * @param rowType The row type for column info
	 * @return The result as an ACell
	 */
	public static ACell evaluate(RexNode expr, ACell[] row, RelDataType rowType) {
		if (expr instanceof RexInputRef ref) {
			int index = ref.getIndex();
			return (index >= 0 && index < row.length) ? row[index] : null;
		}

		if (expr instanceof RexLiteral lit) {
			return literalToCell(lit);
		}

		if (expr instanceof RexCall call) {
			return evaluateCall(call, row, rowType);
		}

		throw new UnsupportedOperationException("Unsupported expression: " + expr.getClass().getSimpleName());
	}

	/**
	 * Converts a SQL literal to an ACell.
	 */
	public static ACell literalToCell(RexLiteral literal) {
		if (literal.isNull()) {
			return null;
		}

		SqlTypeName typeName = literal.getTypeName();
		return switch (typeName) {
			case INTEGER, SMALLINT, TINYINT -> {
				Number n = (Number) literal.getValue();
				yield CVMLong.create(n.longValue());
			}
			case BIGINT -> {
				Number n = (Number) literal.getValue();
				yield CVMLong.create(n.longValue()); // TODO: Use CVMBigInteger for large values
			}
			case DECIMAL -> {
				BigDecimal bd = literal.getValueAs(BigDecimal.class);
				// If it's a whole number that fits in a long, use CVMLong
				if (bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0) {
					try {
						yield CVMLong.create(bd.longValueExact());
					} catch (ArithmeticException e) {
						// Doesn't fit in long, use double
						yield CVMDouble.create(bd.doubleValue());
					}
				}
				yield CVMDouble.create(bd.doubleValue());
			}
			case DOUBLE, FLOAT, REAL -> {
				Number n = (Number) literal.getValue();
				yield CVMDouble.create(n.doubleValue());
			}
			case CHAR, VARCHAR -> {
				String s = literal.getValueAs(String.class);
				yield Strings.create(s);
			}
			case BOOLEAN -> {
				Boolean b = literal.getValueAs(Boolean.class);
				yield CVMBool.create(b);
			}
			case NULL -> null;
			default -> {
				// Fallback: convert to string
				Object v = literal.getValue();
				yield v != null ? Strings.create(v.toString()) : null;
			}
		};
	}

	/**
	 * Evaluates a function/operator call.
	 */
	private static ACell evaluateCall(RexCall call, ACell[] row, RelDataType rowType) {
		SqlKind kind = call.getKind();
		List<RexNode> operands = call.getOperands();

		// Handle IS NULL / IS NOT NULL specially (don't evaluate operand first)
		if (kind == SqlKind.IS_NULL) {
			ACell val = evaluate(operands.get(0), row, rowType);
			return CVMBool.create(val == null);
		}
		if (kind == SqlKind.IS_NOT_NULL) {
			ACell val = evaluate(operands.get(0), row, rowType);
			return CVMBool.create(val != null);
		}

		// Evaluate operands
		ACell[] args = new ACell[operands.size()];
		for (int i = 0; i < operands.size(); i++) {
			args[i] = evaluate(operands.get(i), row, rowType);
		}

		return switch (kind) {
			// Comparison operators - use RT functions
			case EQUALS -> cellEquals(args[0], args[1]);
			case NOT_EQUALS -> cellNotEquals(args[0], args[1]);
			case LESS_THAN -> RT.lt(args);
			case LESS_THAN_OR_EQUAL -> RT.le(args);
			case GREATER_THAN -> RT.gt(args);
			case GREATER_THAN_OR_EQUAL -> RT.ge(args);

			// Logical operators
			case AND -> {
				for (ACell arg : args) {
					if (!RT.bool(arg)) yield CVMBool.FALSE;
				}
				yield CVMBool.TRUE;
			}
			case OR -> {
				for (ACell arg : args) {
					if (RT.bool(arg)) yield CVMBool.TRUE;
				}
				yield CVMBool.FALSE;
			}
			case NOT -> CVMBool.create(!RT.bool(args[0]));

			// Arithmetic operators - use RT functions
			case PLUS -> cellAdd(args[0], args[1]);
			case MINUS -> RT.minus(args);
			case TIMES -> RT.multiply(args);
			case DIVIDE -> RT.divide(args);
			case MOD -> cellMod(args[0], args[1]);
			case MINUS_PREFIX -> cellNegate(args[0]);

			// String operators
			case LIKE -> cellLike(args[0], args[1]);

			// CAST
			case CAST -> args[0]; // TODO: implement proper casting

			default -> throw new UnsupportedOperationException("Operator not supported: " + kind);
		};
	}

	// ========== Cell Operations ==========

	/**
	 * Numeric-aware equality check.
	 * Uses RT.compare for numeric types to handle mixed Long/Double comparison.
	 */
	private static CVMBool cellEquals(ACell a, ACell b) {
		if (a == null && b == null) return CVMBool.TRUE;
		if (a == null || b == null) return CVMBool.FALSE;

		// Use RT.compare for numeric comparison (handles mixed types)
		if (RT.isNumber(a) && RT.isNumber(b)) {
			Long cmp = RT.compare(a, b, null);
			if (cmp == null) return CVMBool.FALSE; // NaN case
			return CVMBool.create(cmp == 0);
		}

		return CVMBool.create(Cells.equals(a, b));
	}

	private static CVMBool cellNotEquals(ACell a, ACell b) {
		if (a == null && b == null) return CVMBool.FALSE;
		if (a == null || b == null) return CVMBool.TRUE;

		if (RT.isNumber(a) && RT.isNumber(b)) {
			Long cmp = RT.compare(a, b, null);
			if (cmp == null) return CVMBool.TRUE; // NaN case
			return CVMBool.create(cmp != 0);
		}

		return CVMBool.create(!Cells.equals(a, b));
	}

	/**
	 * Addition with string concatenation support.
	 */
	private static ACell cellAdd(ACell a, ACell b) {
		if (a == null || b == null) return null;

		// Try numeric addition first
		if (RT.isNumber(a) && RT.isNumber(b)) {
			return RT.plus(new ACell[]{a, b});
		}

		// String concatenation
		if (a instanceof AString || b instanceof AString) {
			return Strings.create(a.toString() + b.toString());
		}

		throw new UnsupportedOperationException("Cannot add: " + a.getClass() + " + " + b.getClass());
	}

	/**
	 * Modulo operation using CVM integer operations.
	 */
	private static ACell cellMod(ACell a, ACell b) {
		if (a == null || b == null) return null;

		// Integer mod
		if (a instanceof AInteger ia && b instanceof AInteger ib) {
			return ia.rem(ib);
		}

		// Double mod
		ANumeric na = RT.ensureNumber(a);
		ANumeric nb = RT.ensureNumber(b);
		if (na != null && nb != null) {
			double da = na.doubleValue();
			double db = nb.doubleValue();
			return CVMDouble.create(da % db);
		}

		throw new UnsupportedOperationException("Cannot mod: " + a.getClass() + " % " + b.getClass());
	}

	/**
	 * Negation using ANumeric.negate().
	 */
	private static ACell cellNegate(ACell a) {
		if (a == null) return null;

		ANumeric n = RT.ensureNumber(a);
		if (n != null) {
			return n.negate();
		}

		throw new UnsupportedOperationException("Cannot negate: " + a.getClass());
	}

	/**
	 * SQL LIKE pattern matching.
	 */
	private static ACell cellLike(ACell a, ACell b) {
		if (a == null || b == null) return CVMBool.FALSE;
		String str = a.toString();
		String pattern = b.toString();
		// Convert SQL LIKE pattern to regex
		String regex = pattern
			.replace(".", "\\.")
			.replace("*", "\\*")
			.replace("%", ".*")
			.replace("_", ".");
		return CVMBool.create(str.matches(regex));
	}
}
