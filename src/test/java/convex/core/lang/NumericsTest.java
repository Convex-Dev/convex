package convex.core.lang;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertCastError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 
 * "You know, people think mathematics is complicated. Mathematics is the simple
 * bit. Its the stuff we can understand. Its cats that are complicated. I mean,
 * what is it in those little molecules and stuff that make one cat behave
 * differently than another, or that make a cat? And how do you define a cat? I
 * have no idea."
 * 
 * - John Conway
 */
public class NumericsTest {

	@Test
	public void testIncDec() {
		assertEquals(1L, (long) eval("(inc (byte 256))"));
		assertEquals(2L, (long) eval("(inc 1)"));
		assertEquals(3L, (long) eval("(dec 4)"));
		assertEquals(4L, (long) eval("(inc (dec 4))"));

		assertCastError(step("(inc nil)"));
		assertCastError(step("(dec nil)"));

	}

	@Test
	public void testPlus() {
		assertEquals(0L, (long) eval("(+)"));
		assertEquals(1L, (long) eval("(+ 1)"));
		assertEquals(2L, (long) eval("(+ 3 -1)"));
		assertEquals(3L, (long) eval("(+ 1 2)"));
		assertEquals(4L, (long) eval("(+ 0 1 2 1)"));

		// long wrap round 64-bit signed
		assertEquals(-2, (long) eval("(+ 9223372036854775807 9223372036854775807)"));
		assertEquals(0, (long) eval("(+ -9223372036854775808 -9223372036854775808)"));
	}

	@Test
	public void testPlusDouble() {
		assertEquals(1.0, (double) eval("(+ 1.0)"));
		assertEquals(2.0, (double) eval("(+ 3 -1.0)"));
		assertEquals(3.0, (double) eval("(+ 1.0 2)"));
		assertEquals(4.0, (double) eval("(+ 0 1 2.0 1)"));

		assertCastError(step("(+ nil)"));
	}

	@Test
	public void testMinus() {
		assertEquals(1L, (long) eval("(- -1)"));
		assertEquals(2L, (long) eval("(- 3 1)"));
		assertEquals(3L, (long) eval("(- 6 1 2)"));
		assertEquals(4L, (long) eval("(- 10 1 -2 7)"));

		assertCastError(step("(- nil)"));
	}

	@Test
	public void testMinusDouble() {
		assertEquals(1.0, (double) eval("(- -1.0)"));
		assertEquals(2.0, (double) eval("(- 3 1.0)"));
		assertEquals(3.0, (double) eval("(- 6.0 1 2)"));
		assertEquals(4.0, (double) eval("(- 10 1.0 -2 7)"));
		assertEquals(5.0, (double) eval("(- 7.5 2.5)"));
	}

	@Test
	public void testMinusNoArgs() {
		assertArityError(step("(-)"));
	}

	@Test
	public void testTimes() {
		assertEquals(0L, (long) eval("(* 0 10)"));
		assertEquals(1L, (long) eval("(*)"));
		assertEquals(20L, (long) eval("(* 2 10)"));
		assertEquals(120L, (long) eval("(* 1 2 3 4 5)"));

		// long wrap round 64 bits
		assertEquals(0L, (long) eval("(* 65536 65536 65536 65536)"));
		assertEquals(Long.MIN_VALUE, (long) eval("(* 32768 65536 65536 65536)"));

		assertCastError(step("(* nil)"));
		assertCastError(step("(* :foo)"));
	}

	@Test
	public void testTimesDouble() {
		assertEquals(0.0, (double) eval("(* 0 10.0)"));
		assertEquals(2.25, (double) eval("(* -1.5 -1.5)"));
	}

	@Test
	public void testDouble() {
		assertEquals(1.0, (double) eval("1.0"));
		assertEquals(1.0, (double) eval("(double 1)"));
		assertEquals(-1.0, (double) eval("-1.0"));
		assertEquals(Double.NaN, (double) eval("(double NaN)"));
	}

	@Test
	public void testDivide() {
		assertEquals(0.5, (double) eval("(/ 2.0)"), 0);
		assertEquals(0.5, (double) eval("(/ 1 2.0)"), 0);
		assertEquals(0.25, (double) eval("(/ 1 2 2)"), 0);
		assertEquals(-4.0, (double) eval("(/ 2 -0.5)"), 0);
		assertEquals(0.5, (double) eval("(/ 2)"), 0);

		assertEquals(Double.NaN, (double) eval("(/ 0.0 0.0)"), 0);
		assertEquals(Double.POSITIVE_INFINITY, (double) eval("(/ 2.0 0.0)"), 0);
		assertEquals(Double.NEGATIVE_INFINITY, (double) eval("(/ -2.0 0.0)"), 0);

		assertCastError(step("(/ nil)"));
		assertCastError(step("(/ 1 :foo)"));
		assertCastError(step("(/ 'foo 1)"));

		assertArityError(step("(/)"));
	}

	@Test
	public void testNaNPropagation() {
		assertEquals(Double.NaN, (double) eval("NaN"), 0);
		assertEquals(Double.NaN, (double) eval("(+ 1 NaN)"), 0);
		assertEquals(Double.NaN, (double) eval("(/ NaN 2)"), 0);
		assertEquals(Double.NaN, (double) eval("(* 1 NaN 3.0)"), 0);
	}

	@Test
	public void testZero() {
		assertTrue((boolean) eval("(== 0 -0)"));
		assertTrue((boolean) eval("(== 0.0 -0.0)"));
		assertTrue((boolean) eval("(== 0 -0.0)"));
		assertTrue((boolean) eval("(<= 0 -0)"));
	}

	@Test
	public void testSqrt() {
		assertEquals(2.0, (double) eval("(sqrt 4.0)"), 0);
		assertEquals(0.0, (double) eval("(sqrt 0.0)"), 0);
		assertEquals(Double.NaN, (double) eval("(sqrt -3)"), 0);
		assertEquals(Double.NaN, (double) eval("(sqrt NaN)"), 0);

		assertArityError(step("(sqrt)"));
		assertArityError(step("(sqrt :foo :bar)")); // arity before cast error

		assertCastError(step("(sqrt :foo)"));
		assertCastError(step("(sqrt nil)"));

	}

	@Test
	public void testExp() {
		assertEquals(1.0, (double) eval("(exp 0.0)"), 0);

		assertEquals(StrictMath.exp(1.0), (double) eval("(exp 1.0)"));
		assertEquals(Math.E, eval("(exp 1.0)"), 0.000001);
		assertEquals(0.0, (double) eval("(exp -100000000.0)"), 0);
		assertEquals(Double.POSITIVE_INFINITY, (double) eval("(exp 100000000)"), 0);

		assertArityError(step("(exp)"));
		assertArityError(step("(exp 1 2)"));
	}

	@Test
	public void testPow() {
		assertEquals(1.0, (double) eval("(pow 1.0 1.0)"), 0);
		assertEquals(1.0, (double) eval("(pow 3.0 0.0)"), 0);
		assertEquals(2.0, (double) eval("(pow 4 0.5)"), 0);

		assertEquals(StrictMath.pow(1.2, 3.5), (double) eval("(pow 1.2,3.5)"));
		assertEquals(0.0, (double) eval("(pow 2 -100000000.0)"), 0);
		assertEquals(Double.POSITIVE_INFINITY, (double) eval("(pow 3 100000000)"), 0);

		assertEquals(Double.NaN, (double) eval("(pow -1.0 1.5)"), 0);
		assertEquals(-1.0, (double) eval("(pow -1.0 7)"), 0);

		assertArityError(step("(pow)"));
		assertArityError(step("(pow 1)"));
		assertArityError(step("(pow 1 2 3)"));
	}

	@Test
	public void testApply() {
		assertEquals(6L, (long) eval("(apply + [1 2 3])"), 0);
		assertEquals(2L, (long) eval("(apply inc [1])"), 0);
		assertEquals(1L, (long) eval("(apply * [])"), 0);
		assertEquals(0L, (long) eval("(apply + nil)"), 0);
	}

	@Test
	public void testCasts() {
		assertEquals(0L, (long) eval("(long (byte 256))"));
		assertEquals(1L, (long) eval("(long 1)"));
		assertEquals(1, (int) eval("(int 1)"));
		assertEquals('a', (char) eval("(char 97)"));
		assertEquals(97L, (long) eval("(long \\a)"));
		assertEquals((short) 1, (short) eval("(short 65537)"));
		assertEquals((byte) 1, (byte) eval("(byte 1)"));
	}

	@Test
	public void testBadStringCast() {
		assertCastError(step("(inc (str 1))"));
	}

}
