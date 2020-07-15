package convex.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import convex.core.ErrorType;
import convex.core.lang.Context;
import convex.core.util.Utils;

public class Assertions {

	public static void assertTotalRefCount(long expected, Object o) {
		long count = Utils.totalRefCount(o);
		assertEquals(expected, count,
				() -> "Wrong number of Refs, expected " + expected + " but got " + o + " in object " + o);
	}

	public static void assertError(ErrorType et, Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(et, cet, "Expected error type " + et + " but got result: " + ctx.getValue());
	}

	public static void assertArityError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.ARITY, cet, "Expected ARITY error but got result: " + ctx.getValue());
	}

	public static void assertCompileError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.COMPILE, cet, "Expected COMPILE error but got result: " + ctx.getValue());
	}

	public static void assertBoundsError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.BOUNDS, cet, "Expected BOUNDS error but got result: " + ctx.getValue());
	}

	public static void assertCastError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.CAST, cet, "Expected CAST error but got result: " + ctx.getValue());
	}

	public static void assertDepthError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.DEPTH, cet, "Expected DEPTH error but got: " + ctx.getValue());
	}

	public static void assertJuiceError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.JUICE, cet, "Expected JUICE error but got: " + ctx.getValue());
	}

	public static void assertUndeclaredError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.UNDECLARED, cet, "Expected UNDECLARED error but got: " + ctx.getValue());
	}

	public static void assertStateError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.STATE, cet, "Expected STATE error but got: " + ctx.getValue());
	}

	public static void assertArgumentError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.ARGUMENT, cet, "Expected ARGUMENT error but got: " + ctx.getValue());
	}

	public static void assertFundsError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.FUNDS, cet, "Expected FUNDS error but got: " + ctx.getValue());
	}
	
	public static void assertNobodyError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.NOBODY, cet, "Expected NOBODY error but got: " + ctx.getValue());
	}

	public static void assertSequenceError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.SEQUENCE, cet, "Expected SEQUENCE error but got: " + ctx.getValue());
	}

	public static void assertAssertError(Context<?> ctx) {
		ErrorType cet = ctx.getErrorType();
		assertEquals(ErrorType.ASSERT, cet, "Expected ASSERT error but got: " + ctx.getValue());
	}
}
