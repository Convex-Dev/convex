package convex.core.lang;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.evalB;
import static convex.core.lang.TestState.evalD;
import static convex.core.lang.TestState.evalL;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMDouble;

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
		assertEquals(1L, evalL("(inc (byte 256))"));
		assertEquals(2L, evalL("(inc 1)"));
		assertEquals(3L, evalL("(dec 4)"));
		assertEquals(4L, evalL("(inc (dec 4))"));

		assertCastError(step("(inc nil)"));
		assertCastError(step("(dec nil)"));
		assertCastError(step("(inc :foo)"));

	}

	@Test
	public void testPlus() {
		assertEquals(0L, evalL("(+)"));
		assertEquals(1L, evalL("(+ 1)"));
		assertEquals(2L, evalL("(+ 3 -1)"));
		assertEquals(3L, evalL("(+ 1 2)"));
		assertEquals(4L, evalL("(+ 0 1 2 1)"));

		// long wrap round 64-bit signed
		assertEquals(-2, evalL("(+ 9223372036854775807 9223372036854775807)"));
		assertEquals(0, evalL("(+ -9223372036854775808 -9223372036854775808)"));
		
	}

	@Test
	public void testPlusDouble() {
		assertEquals(1.0, evalD("(+ 1.0)"));
		assertEquals(2.0, evalD("(+ 3 -1.0)"));
		assertEquals(3.0, evalD("(+ 1.0 2)"));
		assertEquals(4.0, evalD("(+ 0 1 2.0 1)"));

		assertCastError(step("(+ nil)"));
		assertCastError(step("(+ 1.0 :foo)"));
		assertCastError(step("(+ 1.0 nil 2)"));
	}

	@Test
	public void testMinus() {
		assertEquals(1L, evalL("(- -1)"));
		assertEquals(2L, evalL("(- 3 1)"));
		assertEquals(3L, evalL("(- 6 1 2)"));
		assertEquals(4L, evalL("(- 10 1 -2 7)"));

		assertCastError(step("(- nil)"));
		assertCastError(step("(- 1 [])"));
	}
	
	@Test
	public void testDivisionConsistency() {
		// Check consistency of quot and rem
		assertNotError(step("(map (fn [[a b]] (or (== a (+ (* b (quot a b)) (rem a b) ) ) (fail [a b])) ) [[10 3] [-10 3] [10 -3] [-10 -3] [10000000 1] [1 10000000]])"));

	    // Check modular behaviour (mod a b) == (mod (mod a b) b)
		assertNotError(step("(map (fn [[a b]] (or (== (mod a b) (mod (mod a b) b)) (fail [a b])) ) [[10 3] [-10 3] [10 -3] [-10 -3] [10000000 1] [1 10000000]])"));
	}

	@Test
	public void testMinusDouble() {
		assertEquals(1.0, evalD("(- -1.0)"));
		assertEquals(2.0, evalD("(- 3 1.0)"));
		assertEquals(3.0, evalD("(- 6.0 1 2)"));
		assertEquals(4.0, evalD("(- 10 1.0 -2 7)"));
		assertEquals(5.0, evalD("(- 7.5 2.5)"));
	}

	@Test
	public void testMinusNoArgs() {
		assertArityError(step("(-)"));
	}

	@Test
	public void testTimes() {
		assertEquals(0L, evalL("(* 0 10)"));
		assertEquals(1L, evalL("(*)"));
		assertEquals(20L, evalL("(* 2 10)"));
		assertEquals(120L, evalL("(* 1 2 3 4 5)"));

		// long wrap round 64 bits
		assertEquals(0L, evalL("(* 65536 65536 65536 65536)"));
		assertEquals(Long.MIN_VALUE, evalL("(* 32768 65536 65536 65536)"));

		assertCastError(step("(* nil)"));
		assertCastError(step("(* :foo)"));
	}

	@Test
	public void testTimesDouble() {
		assertEquals(0.0, evalD("(* 0 10.0)"));
		assertEquals(5.0, evalD("(* 0.5 10)"));
		
		assertEquals(50.0, evalD("(* 10 2.5 2)"));

		assertEquals(2.25, evalD("(* -1.5 -1.5)"));
	}

	@Test
	public void testDouble() {
		assertEquals(1.0, evalD("1.0"));
		assertEquals(1.0, evalD("(double 1)"));
		assertEquals(-1.0, evalD("-1.0"));
		assertEquals(Double.NaN, evalD("(double ##NaN)"));
	}

	@Test
	public void testDivide() {
		assertEquals(0.5, evalD("(/ 2.0)"), 0);
		assertEquals(0.5, evalD("(/ 1 2.0)"), 0);
		assertEquals(0.25, evalD("(/ 1 2 2)"), 0);
		assertEquals(-4.0, evalD("(/ 2 -0.5)"), 0);
		assertEquals(0.5, evalD("(/ 2)"), 0);

		assertEquals(Double.NaN, evalD("(/ 0.0 0.0)"), 0);
		assertEquals(Double.POSITIVE_INFINITY, evalD("(/ 2.0 0.0)"), 0);
		assertEquals(Double.NEGATIVE_INFINITY, evalD("(/ -2.0 0.0)"), 0);

		assertCastError(step("(/ nil)"));
		assertCastError(step("(/ 1 :foo)"));
		assertCastError(step("(/ #7 #0)"));
		assertCastError(step("(/ 'foo 1)"));

		assertArityError(step("(/)"));
	}

	@Test
	public void testNaNPropagation() {
		assertEquals(Double.NaN, evalD("##NaN"), 0);
		assertEquals(Double.NaN, evalD("(+ 1 ##NaN)"), 0);
		assertEquals(Double.NaN, evalD("(/ ##NaN 2)"), 0);
		assertEquals(Double.NaN, evalD("(* 1 ##NaN 3.0)"), 0);
	}
	
	@Test
	public void testNaNBehaviour() {
		assertEquals(Double.NaN, evalD("##NaN"), 0);
		assertTrue(evalB("(= ##NaN ##NaN)"));
		
		// match Java primitives for equality as IEE754. All NaN comparisons should be false
		assertFalse(Double.NaN==Double.NaN);
		assertFalse(evalB("(== ##NaN ##NaN)"));
		assertFalse(evalB("(< ##NaN ##NaN)"));
		assertFalse(evalB("(>= ##NaN ##NaN)"));
		assertFalse(evalB("(>= ##NaN 1.0)"));
		assertFalse(evalB("(< 1 ##NaN)"));
		
		// TODO: should this be in core? NaN is not equal to itself
		// assertTrue(evalB("(!= ##NaN ##NaN)"));
	}
	
	@Test
	public void testInfinity() {
		assertEquals(CVMDouble.POSITIVE_INFINITY, eval("(/ 1 0)"));
		assertEquals(CVMDouble.NEGATIVE_INFINITY, eval("(/ -1 0)"));
		
		assertEquals(CVMDouble.NEGATIVE_INFINITY, eval("(- ##Inf)"));
		assertEquals(CVMDouble.POSITIVE_INFINITY, eval("(- ##-Inf)"));

		assertTrue(evalB("(== ##Inf ##Inf)"));
		assertFalse(evalB("(== ##Inf ##-Inf)"));
	}

	@Test
	public void testZero() {
		assertTrue(evalB("(== 0 -0)"));
		assertTrue(evalB("(== 0.0 -0.0)"));
		assertTrue(evalB("(== 0 -0.0)"));
		assertTrue(evalB("(<= 0 -0)"));
		assertTrue(evalB("(>= 0 0)"));

	}
	
	@Test
	public void testSignum() {
		assertEquals(CVMDouble.NEGATIVE_ZERO,eval("(signum -0.0)"));
		assertEquals(CVMDouble.ZERO,eval("(signum 0.0)"));
		assertEquals(CVMDouble.ONE,eval("(signum 13.3)"));
		assertEquals(CVMDouble.MINUS_ONE,eval("(signum (/ -1 0))"));
		assertEquals(CVMDouble.ONE,eval("(signum ##Inf)"));
		assertEquals(CVMDouble.NaN,eval("(signum (sqrt -1))"));
	}


	@Test
	public void testSqrt() {
		assertEquals(2.0, evalD("(sqrt 4.0)"), 0);
		assertEquals(0.0, evalD("(sqrt 0.0)"), 0);
		assertEquals(Double.NaN, evalD("(sqrt -3)"), 0);
		assertEquals(Double.NaN, evalD("(sqrt ##NaN)"), 0);

		assertArityError(step("(sqrt)"));
		assertArityError(step("(sqrt :foo :bar)")); // arity before cast error

		assertCastError(step("(sqrt :foo)"));
		assertCastError(step("(sqrt nil)"));

	}

	@Test
	public void testExp() {
		assertEquals(1.0, evalD("(exp 0.0)"), 0);

		assertEquals(StrictMath.exp(1.0), evalD("(exp 1.0)"));
		assertEquals(Math.E, evalD("(exp 1.0)"), 0.000001);
		assertEquals(0.0, evalD("(exp -100000000.0)"), 0);
		assertEquals(Double.POSITIVE_INFINITY, evalD("(exp 100000000)"), 0);

		assertArityError(step("(exp)"));
		assertArityError(step("(exp 1 2)"));
	}

	@Test
	public void testPow() {
		assertEquals(1.0, evalD("(pow 1.0 1.0)"), 0);
		assertEquals(1.0, evalD("(pow 3.0 0.0)"), 0);
		assertEquals(2.0, evalD("(pow 4 0.5)"), 0);

		assertEquals(StrictMath.pow(1.2, 3.5), evalD("(pow 1.2,3.5)"));
		assertEquals(0.0, evalD("(pow 2 -100000000.0)"), 0);
		assertEquals(Double.POSITIVE_INFINITY,evalD("(pow 3 100000000)"), 0);

		assertEquals(Double.NaN, evalD("(pow -1.0 1.5)"), 0);
		assertEquals(-1.0, evalD("(pow -1.0 7)"), 0);

		assertArityError(step("(pow)"));
		assertArityError(step("(pow 1)"));
		assertArityError(step("(pow 1 2 3)"));
	}

	@Test
	public void testApply() {
		assertEquals(6L, evalL("(apply + [1 2 3])"), 0);
		assertEquals(2L, evalL("(apply inc [1])"), 0);
		assertEquals(1L, evalL("(apply * [])"), 0);
		assertEquals(0L, evalL("(apply + nil)"), 0);
	}
	
	@Test
	public void testHexCasts() {
		assertEquals(3L, evalL("(+ (long 0x01) (byte 0x02))"));
		
		// byte cast wraps over
		assertSame(CVMByte.create(-1), eval("(byte 0xFF)"));
		
		// check we are treating blobs as unsigned values
		assertEquals(510L, evalL("(+ (long 0xFF) (long 0xFF))"));
		assertEquals(-2L, evalL("(+ (long 0xFFFFFFFFFFFFFFFF) (long 0xFFFFFFFFFFFFFFFF))"));
		
		// take low order bytes of big long
		assertEquals(-1L, evalL("(long 0x0000000000000000FFFFFFFFFFFFFFFF)"));
	}
	
	@Test
	public void testBadArgs() {
		// Regression check for issue #89
		assertCastError(step("(+ 1 #42)"));
		assertCastError(step("(+ #42 1.0)"));
	}

	@Test
	public void testCasts() {
		assertEquals(0L, evalL("(long (byte 256))"));
		assertEquals(13L, evalL("(long #13)"));
		assertEquals(255L, evalL("(long 0xff)"));
		assertEquals(1L, evalL("(long 1)"));
		assertCVMEquals('a', eval("(char 97)"));
		assertEquals(97L, evalL("(long \\a)"));
		assertSame(CVMByte.create(1), eval("(byte 1)"));
	}

	@Test
	public void testBadStringCast() {
		assertCastError(step("(inc (str 1))"));
	}

}
