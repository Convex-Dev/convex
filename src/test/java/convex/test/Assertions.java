package convex.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import convex.core.ErrorCodes;
import convex.core.lang.Context;
import convex.core.util.Utils;

public class Assertions {

	public static void assertTotalRefCount(long expected, Object o) {
		long count = Utils.totalRefCount(o);
		assertEquals(expected, count,
				() -> "Wrong number of Refs, expected " + expected + " but got " + o + " in object " + o);
	}

	public static void assertError(Object et, Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(et, cet, "Expected error type " + et + " but got result: " + ctx.getValue());
	}

	public static void assertArityError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.ARITY, cet, "Expected ARITY error but got result: " + ctx.getValue());
	}

	public static void assertCompileError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.COMPILE, cet, "Expected COMPILE error but got result: " + ctx.getValue());
	}

	public static void assertBoundsError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.BOUNDS, cet, "Expected BOUNDS error but got result: " + ctx.getValue());
	}

	public static void assertCastError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.CAST, cet, "Expected CAST error but got result: " + ctx.getValue());
	}

	public static void assertDepthError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.DEPTH, cet, "Expected DEPTH error but got: " + ctx.getValue());
	}

	public static void assertJuiceError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.JUICE, cet, "Expected JUICE error but got: " + ctx.getValue());
	}

	public static void assertUndeclaredError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.UNDECLARED, cet, "Expected UNDECLARED error but got: " + ctx.getValue());
	}

	public static void assertStateError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.STATE, cet, "Expected STATE error but got: " + ctx.getValue());
	}

	public static void assertArgumentError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.ARGUMENT, cet, "Expected ARGUMENT error but got: " + ctx.getValue());
	}
	
	public static void assertMemoryError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.MEMORY, cet, "Expected MEMORY error but got: " + ctx.getValue());
	}


	public static void assertFundsError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.FUNDS, cet, "Expected FUNDS error but got: " + ctx.getValue());
	}
	
	public static void assertNobodyError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.NOBODY, cet, "Expected NOBODY error but got: " + ctx.getValue());
	}

	public static void assertSequenceError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.SEQUENCE, cet, "Expected SEQUENCE error but got: " + ctx.getValue());
	}

	public static void assertAssertError(Context<?> ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.ASSERT, cet, "Expected ASSERT error but got: " + ctx.getValue());
	}
}
