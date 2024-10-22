package convex.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.function.Executable;

import convex.core.ErrorCodes;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.Refs;
import convex.core.data.prim.ANumeric;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;

public class Assertions {
	
	public static void assertNotError(Context ctx) {
		if(ctx.isExceptional()) {
			fail("Expected no error but got: " + ctx.getValue());
		}
	}

	public static void assertTotalRefCount(long expected, ACell o) {
		long count = Refs.totalRefCount(o);
		assertEquals(expected, count,
				() -> "Wrong number of Refs, expected " + expected + " but got " + o + " in object " + o);
	}
	
	public static void assertCVMEquals(Object expected, Object result) {
		assertEquals((Object)RT.cvm(expected),RT.cvm(result));
	}

	public static void assertError(Object et, Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(et, cet, "Expected error type " + et + " but got result: " + ctx.getValue());
	}
	
	public static void assertError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertNotNull(cet, "Expected an error but got result: " + ctx.getValue());
	}

	public static void assertArityError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.ARITY, cet, "Expected ARITY error but got result: " + ctx.getValue());
	}
	
	public static void assertTrustError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.TRUST, cet, "Expected TRUST error but got result: " + ctx.getValue());
	}

	public static void assertCompileError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.COMPILE, cet, "Expected COMPILE error but got result: " + ctx.getValue());
	}

	public static void assertBoundsError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.BOUNDS, cet, "Expected BOUNDS error but got result: " + ctx.getValue());
	}

	public static void assertCastError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.CAST, cet, "Expected CAST error but got result: " + ctx.getValue());
	}

	public static void assertDepthError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.DEPTH, cet, "Expected DEPTH error but got: " + ctx.getValue());
	}

	public static void assertJuiceError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.JUICE, cet, "Expected JUICE error but got: " + ctx.getValue());
	}

	public static void assertUndeclaredError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.UNDECLARED, cet, "Expected UNDECLARED error but got: " + ctx.getValue());
	}

	public static void assertStateError(Context ctx) {
		Object cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.STATE, cet, "Expected STATE error but got: " + ctx.getValue());
	}

	public static void assertArgumentError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.ARGUMENT, cet, "Expected ARGUMENT error but got: " + ctx.getValue());
	}
	
	public static void assertMemoryError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.MEMORY, cet, "Expected MEMORY error but got: " + ctx.getValue());
	}
	
	public static void assertSyntaxError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.SYNTAX, cet, "Expected SYNTAX error but got: " + ctx.getValue());
	}

	public static void assertFundsError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.FUNDS, cet, "Expected FUNDS error but got: " + ctx.getValue());
	}
	
	public static void assertNobodyError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.NOBODY, cet, "Expected NOBODY error but got: " + ctx.getValue());
	}

	public static void assertSequenceError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.SEQUENCE, cet, "Expected SEQUENCE error but got: " + ctx.getValue());
	}

	public static void assertAssertError(Context ctx) {
		ACell cet = ctx.getErrorCode();
		assertEquals(ErrorCodes.ASSERT, cet, "Expected ASSERT error but got: " + ctx.getValue());
	}
	
	public static void assertLess(long a, long b) {
		if (a<b) return; //OK
		fail("Expected "+a+" to be less than "+b);
	}
	
	public static void assertLess(double a, double b) {
		if (a<b) return; //OK
		fail("Expected "+a+" to be less than "+b);
	}
	
	public static void assertLess(ANumeric  a, ANumeric b) {
		if (RT.compare(a, b, 1L)<0) return; //OK
		fail("Expected "+a+" to be less than "+b);
	}
	
	public static void assertGreater(long a, long b) {
		if (a>b) return; //OK
		fail("Expected "+a+" to be greater than "+b);
	}
	
	public static void assertGreater(double a, double b) {
		if (a>b) return; //OK
		fail("Expected "+a+" to be greater than "+b);
	}
	
	public static void assertGreater(ANumeric  a, ANumeric b) {
		if (RT.compare(a, b, -1L)>0) return; //OK
		fail("Expected "+a+" to be greater than "+b);
	}

	public static void assertParseException(Executable a) {
		assertThrows(ParseException.class,a);
	}
}
