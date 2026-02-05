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
import convex.core.data.ABlob;

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

		// Handle CASE (short-circuit evaluation)
		if (kind == SqlKind.CASE) {
			return evaluateCase(operands, row, rowType);
		}

		// Handle COALESCE (short-circuit evaluation)
		if (kind == SqlKind.COALESCE) {
			for (RexNode operand : operands) {
				ACell val = evaluate(operand, row, rowType);
				if (val != null) return val;
			}
			return null;
		}

		// Handle IN (short-circuit evaluation)
		if (kind == SqlKind.IN) {
			ACell lhs = evaluate(operands.get(0), row, rowType);
			for (int i = 1; i < operands.size(); i++) {
				ACell rhs = evaluate(operands.get(i), row, rowType);
				if (cellEquals(lhs, rhs) == CVMBool.TRUE) return CVMBool.TRUE;
			}
			return CVMBool.FALSE;
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
			case IS_NOT_DISTINCT_FROM -> cellIsNotDistinctFrom(args[0], args[1]);
			case IS_DISTINCT_FROM -> cellIsDistinctFrom(args[0], args[1]);
			case LESS_THAN -> RT.lt(args);
			case LESS_THAN_OR_EQUAL -> RT.le(args);
			case GREATER_THAN -> RT.gt(args);
			case GREATER_THAN_OR_EQUAL -> RT.ge(args);

			// BETWEEN: a BETWEEN b AND c -> a >= b AND a <= c
			case BETWEEN -> {
				ACell a = args[0], lo = args[1], hi = args[2];
				CVMBool geq = (CVMBool) RT.ge(new ACell[]{a, lo});
				CVMBool leq = (CVMBool) RT.le(new ACell[]{a, hi});
				yield CVMBool.create(geq.booleanValue() && leq.booleanValue());
			}

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

			// CAST - proper implementation using RT functions
			case CAST -> evaluateCast(args[0], call.getType());

			// Handle OTHER (function calls like ABS, FLOOR, etc.)
			case OTHER_FUNCTION -> evaluateFunction(call, args);

			default -> throw new UnsupportedOperationException("Operator not supported: " + kind);
		};
	}

	/**
	 * Evaluates CASE WHEN expressions.
	 */
	private static ACell evaluateCase(List<RexNode> operands, ACell[] row, RelDataType rowType) {
		// CASE WHEN cond1 THEN val1 WHEN cond2 THEN val2 ... ELSE default END
		// Operands: [cond1, val1, cond2, val2, ..., default]
		// If odd number, last is ELSE value; if even, no ELSE (returns null)
		int n = operands.size();
		boolean hasElse = (n % 2) == 1;
		int pairs = hasElse ? (n - 1) / 2 : n / 2;

		for (int i = 0; i < pairs; i++) {
			ACell cond = evaluate(operands.get(i * 2), row, rowType);
			if (RT.bool(cond)) {
				return evaluate(operands.get(i * 2 + 1), row, rowType);
			}
		}

		// Return ELSE value or null
		return hasElse ? evaluate(operands.get(n - 1), row, rowType) : null;
	}

	/**
	 * Evaluates CAST operations using RT functions.
	 */
	private static ACell evaluateCast(ACell value, RelDataType targetType) {
		if (value == null) return null;

		SqlTypeName typeName = targetType.getSqlTypeName();
		return switch (typeName) {
			case BIGINT, INTEGER, SMALLINT, TINYINT -> RT.castLong(value);
			case DOUBLE, FLOAT, REAL -> RT.castDouble(value);
			case VARCHAR, CHAR -> {
				if (value instanceof AString) yield value;
				yield Strings.create(value.toString());
			}
			case BOOLEAN -> CVMBool.create(RT.bool(value));
			default -> value; // Pass through
		};
	}

	/**
	 * Evaluates SQL functions using RT math functions.
	 */
	private static ACell evaluateFunction(RexCall call, ACell[] args) {
		String funcName = call.getOperator().getName().toUpperCase();

		return switch (funcName) {
			// Math functions using RT
			case "ABS" -> RT.abs(args[0]);
			case "FLOOR" -> RT.floor(args[0]);
			case "CEIL", "CEILING" -> RT.ceil(args[0]);
			case "SQRT" -> RT.sqrt(args[0]);
			case "EXP" -> RT.exp(args[0]);
			case "POWER", "POW" -> RT.pow(args);
			case "SIGN", "SIGNUM" -> RT.signum(args[0]);

			// String functions (using Java String methods since no CVM equivalents)
			case "UPPER" -> cellUpper(args[0]);
			case "LOWER" -> cellLower(args[0]);
			case "TRIM" -> cellTrim(args[0]);
			case "LTRIM" -> cellLTrim(args[0]);
			case "RTRIM" -> cellRTrim(args[0]);
			case "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH" -> cellLength(args[0]);
			case "SUBSTRING", "SUBSTR" -> cellSubstring(args);
			case "CONCAT" -> RT.str(args);
			case "POSITION", "LOCATE" -> cellPosition(args);
			case "REPLACE" -> cellReplace(args);

			// Aggregate-related MIN/MAX (when used as scalar)
			case "MIN" -> RT.min(args);
			case "MAX" -> RT.max(args);

			default -> throw new UnsupportedOperationException("Function not supported: " + funcName);
		};
	}

	// ========== String Functions ==========
	// Now using native AString methods for upper/lower/trim.

	private static ACell cellUpper(ACell a) {
		if (a == null) return null;
		AString s = RT.ensureString(a);
		if (s == null) return Strings.create(a.toString().toUpperCase());
		return s.toUpperCase();
	}

	private static ACell cellLower(ACell a) {
		if (a == null) return null;
		AString s = RT.ensureString(a);
		if (s == null) return Strings.create(a.toString().toLowerCase());
		return s.toLowerCase();
	}

	private static ACell cellTrim(ACell a) {
		if (a == null) return null;
		AString s = RT.ensureString(a);
		if (s == null) return Strings.create(a.toString().trim());
		return s.trim();
	}

	private static ACell cellLTrim(ACell a) {
		if (a == null) return null;
		// AString doesn't have ltrim, use Java String
		return Strings.create(a.toString().stripLeading());
	}

	private static ACell cellRTrim(ACell a) {
		if (a == null) return null;
		// AString doesn't have rtrim, use Java String
		return Strings.create(a.toString().stripTrailing());
	}

	private static ACell cellLength(ACell a) {
		if (a == null) return null;
		if (a instanceof AString s) {
			// Return character count (using Java String length, not byte count)
			return CVMLong.create(s.toString().length());
		}
		return CVMLong.create(a.toString().length());
	}

	private static ACell cellSubstring(ACell[] args) {
		if (args[0] == null) return null;
		String s = args[0].toString();
		// SQL SUBSTRING is 1-based
		int start = args.length > 1 && args[1] != null
			? ((Number) RT.jvm(args[1])).intValue() - 1 : 0;
		int length = args.length > 2 && args[2] != null
			? ((Number) RT.jvm(args[2])).intValue() : s.length() - start;

		if (start < 0) start = 0;
		int end = Math.min(start + length, s.length());
		if (start >= s.length()) return Strings.EMPTY;

		return Strings.create(s.substring(start, end));
	}

	private static ACell cellPosition(ACell[] args) {
		if (args[0] == null || args[1] == null) return null;
		String needle = args[0].toString();
		String haystack = args[1].toString();
		// SQL POSITION returns 1-based index, 0 if not found
		int pos = haystack.indexOf(needle);
		return CVMLong.create(pos >= 0 ? pos + 1 : 0);
	}

	private static ACell cellReplace(ACell[] args) {
		if (args[0] == null) return null;
		String s = args[0].toString();
		String from = args[1] != null ? args[1].toString() : "";
		String to = args[2] != null ? args[2].toString() : "";
		return Strings.create(s.replace(from, to));
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
	 * IS NOT DISTINCT FROM: treats NULLs as equal.
	 * NULL IS NOT DISTINCT FROM NULL = TRUE
	 * x IS NOT DISTINCT FROM NULL = FALSE (when x is not null)
	 */
	private static CVMBool cellIsNotDistinctFrom(ACell a, ACell b) {
		if (a == null && b == null) return CVMBool.TRUE;
		if (a == null || b == null) return CVMBool.FALSE;
		return cellEquals(a, b);
	}

	/**
	 * IS DISTINCT FROM: treats NULLs as equal (opposite of IS NOT DISTINCT FROM).
	 */
	private static CVMBool cellIsDistinctFrom(ACell a, ACell b) {
		if (a == null && b == null) return CVMBool.FALSE;
		if (a == null || b == null) return CVMBool.TRUE;
		return cellNotEquals(a, b);
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
