package convex.core.lang;

import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.cvm.Context;
import convex.core.data.ObjectsTest;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

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
public class NumericsTest extends ACVMTest {

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
		
		assertEquals(CVMBigInteger.MIN_POSITIVE,eval("(+ 1 9223372036854775807)"));
		assertEquals(CVMBigInteger.MIN_POSITIVE,eval("(+ 9223372036854775807 1)"));

		assertEquals(Reader.read("18446744073709551614"), eval("(+ 9223372036854775807 9223372036854775807)"));
		assertEquals(Reader.read("-18446744073709551616"), eval("(+ -9223372036854775808 -9223372036854775808)"));
		
	}

	@Test
	public void testPlusSpecialCases() {
		AInteger m=CVMLong.MAX_VALUE;
		m=m.add(m);
		assertEquals(Reader.read("18446744073709551614"),m);
		assertEquals(-2,m.longValue());
	}
	
	
	@Test
	public void testPlusDouble() {
		assertEquals(1.0, evalD("(+ 1.0)"));
		assertEquals(2.0, evalD("(+ 3 -1.0)"));
		assertEquals(3.0, evalD("(+ 1.0 2)"));
		assertEquals(4.0, evalD("(+ 0 1 2.0 1)"));
		
		// some odd cases with zeros
		assertEquals(-0.0, evalD("(+ -0.0)"));
		assertEquals(-0.0, evalD("(+ -0.0 -0.0)"));
		assertEquals(0.0, evalD("(+ 0.0 -0.0)"));

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

		// TODO FIXME: Check consistency of mod and div
		// assertNotError(step("(map (fn [[a b]] (or (== a (+ (* b (div a b)) (mod a b) ) ) (fail [a b])) ) [[10 3] [-10 3] [10 -3] [-10 -3] [10000000 1] [1 10000000]])"));

	}
	
	@Test
	public void testIntConsistency() {
		doIntConsistencyChecks(156858,-576);
		doIntConsistencyChecks(AInteger.parse("99999999999999999999999999999"),CVMLong.create(1));
		doIntConsistencyChecks(-1,2);
		doIntConsistencyChecks(3,0);
		doIntConsistencyChecks(0,7);
		doIntConsistencyChecks(Long.MAX_VALUE,Long.MIN_VALUE);
		doIntConsistencyChecks(CVMBigInteger.MIN_POSITIVE,Long.MAX_VALUE);
		doIntConsistencyChecks(156756567568L,CVMBigInteger.MIN_NEGATIVE);
	}
	
	private void doIntConsistencyChecks(Object oa, Object ob) {
		AInteger a=AInteger.parse(oa);
		AInteger b=AInteger.parse(oa);
		if (!b.isZero()) {
			assertTrue(evalB("(let [a "+a+" b "+b+"] (== a (+ (* b (quot a b)) (rem a b))) )"));
			assertTrue(evalB("(let [a "+a+" b "+b+"] (== (mod a b) (mod (mod a b) b)) )"));
			assertTrue(evalB("(let [a "+a+" b "+b+"] (== (quot (* a b) b) ))"));
		}
		
		assertTrue(evalB("(let [a "+a+" b "+b+"] (== (- (+ a b) b) ))"));
		
	}
	
	@Test
	public void testArbitraryIntegers() {
		checkIntVal(0);
		checkIntVal(16869696);
		checkIntVal(-16869696);
		checkIntVal(Long.MAX_VALUE);
		checkIntVal(Long.MIN_VALUE);
		checkIntVal(CVMBigInteger.MIN_POSITIVE);
		checkIntVal(CVMBigInteger.MIN_NEGATIVE);
		checkIntVal("676969696986986986969698698698698696969866986");
		checkIntVal("-4524746724376436273417263424376432173471243");
	}
	
	private void checkIntVal(Object o) {
		AInteger a=AInteger.parse(o);
		assertNotNull(a);
		assertTrue(evalB("(let [a "+a+"] (== a (* (abs a) (signum a)) ))"));
		
		doIntegerTests(a);
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

		// long overflow cases
		assertEquals(CVMBigInteger.parse("18446744073709551616"), eval("(* 65536 65536 65536 65536)"));
		assertEquals(CVMBigInteger.MIN_POSITIVE, eval("(* 32768 65536 65536 65536)"));
		assertEquals(CVMLong.MIN_VALUE, eval("(* 32768 65536 -65536 65536)"));

		assertCastError(step("(* nil)"));
		assertCastError(step("(* :foo)"));
	}

	@Test
	public void testTimesDouble() {
		assertEquals(1.0, evalD("(* 1.0)"));
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
	
	@ParameterizedTest
	@ValueSource(doubles = { 0.0, 0.3, 0.7, -1.0, 1.0, -0.6, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Long.MAX_VALUE, Long.MIN_VALUE})
	public void testDoubleSemantics(double d) {
		CVMDouble cd=CVMDouble.create(d);
		assertEquals(d, evalD(cd.toString()));
		assertEquals(d, evalD("(+ 0.0 "+cd+")"));
		assertEquals(d, evalD("(+ 0 "+cd+")"));
		assertEquals(d, evalD("(double "+cd+")"));
		if (Double.isFinite(d)) {
			assertEquals((long)d, evalL("(long "+cd+")"));
		}
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
		assertEquals(Double.NEGATIVE_INFINITY, evalD("(/ -1 0)"), 0);

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
	
	@ParameterizedTest
	@ValueSource(longs = {0,-1,1,-4,7,100,-245,1000,-565865,Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE+1L, Integer.MIN_VALUE-1L,Long.MAX_VALUE, Long.MIN_VALUE})
	public void testLongSemantics(long a) {
		CVMLong ca=CVMLong.create(a);
		assertEquals(a, evalL(ca.toString()));
		assertEquals(ca, eval("(dec (inc "+ca+"))"));
		assertEquals(ca, eval("(long "+ca+")"));
		assertEquals((double)a, evalD("(double "+ca+")"));
		if (a!=0) {
			assertEquals(CVMLong.ZERO,eval("(mod "+ca+" "+ca+")"));
			assertEquals(1.0/a,evalD("(/ 1 "+ca+")"));
		}
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
		
		assertCVMEquals(eval("##NaN"),eval("##NaN"));
	}
	
	@Test
	public void testNullBehaviour() {
		assertCastError(step("(+ nil)"));
		assertCastError(step("(- 1 2 nil)"));
		assertCastError(step("(> 3 nil 2)"));
		assertCastError(step("(sqrt nil)"));
		assertCastError(step("(floor nil)"));
		assertCastError(step("(exp nil)"));
		assertCastError(step("(pow 2 nil)"));
		assertCastError(step("(pow nil 2)"));
		assertCastError(step("(signum nil)"));
		assertCastError(step("(* nil)"));
		assertCastError(step("(/ nil)"));
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
		assertTrue(evalB("(== 0 -0.0)"));
		assertTrue(evalB("(<= 0 -0)"));
		assertTrue(evalB("(>= 0 0)"));
		
		assertFalse(evalB("(= 0.0 -0.0)")); // Seriously, IEEE 754?
		assertTrue(evalB("(== 0.0 -0.0)")); // the least this is sane
	}
	
	@Test
	public void testSignum() {
		assertEquals(CVMDouble.NEGATIVE_ZERO,eval("(signum -0.0)"));
		assertEquals(CVMDouble.ZERO,eval("(signum 0.0)"));
		assertEquals(CVMDouble.ONE,eval("(signum 13.3)"));
		assertEquals(CVMDouble.MINUS_ONE,eval("(signum ##-Inf)"));
		assertEquals(CVMDouble.ONE,eval("(signum ##Inf)"));
		assertEquals(CVMDouble.NaN,eval("(signum ##NaN)"));
	}


	@Test
	public void testSqrt() {
		assertEquals(2.0, evalD("(sqrt 4.0)"), 0);
		assertEquals(0.0, evalD("(sqrt 0.0)"), 0);
		assertEquals(Double.NaN, evalD("(sqrt -3)"), 0);
		assertEquals(Double.NaN, evalD("(sqrt ##NaN)"), 0);
		
		// fun cases
		assertEquals(Double.POSITIVE_INFINITY, evalD("(sqrt ##Inf)"), 0);
		assertEquals(Double.NaN, evalD("(sqrt ##-Inf)"), 0);

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
	public void testFactorial() {
		Context ctx=step("(defn fact ([a] (recur 1 a)) ([acc a] (if (<= a 1) acc (recur (* acc a) (dec a)))))");
		
		assertEquals(24L,evalL(ctx,"(fact 4)"));
		assertEquals("265252859812191058636308480000000", eval(ctx,"(fact 30)").toString());
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
	public void testAbs() {
		assertEquals(0L,evalL("(abs 0)"));
		assertEquals(3L,evalL("(abs -3)"));
		String s="678638762397869864875634897567896587";
		assertEquals(AInteger.parse(s),eval("(abs "+s+")"));
		assertEquals(AInteger.parse(s),eval("(abs -678638762397869864875634897567896587)"));
	}
	
	@Test
	public void testHexCasts() {
		assertEquals(3L, evalL("(+ (long 0x01) (byte 0x02))"));
		
		// byte cast wraps over
		assertSame(CVMLong.forByte((byte)-1), eval("(byte 0xFF)"));
		
		// check we are treating blobs as unsigned values
		assertEquals(-2L, evalL("(+ (long 0xFF) (long 0xFF))"));
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
		assertEquals(-1L, evalL("(long 0xff)"));
		assertEquals(1L, evalL("(long 1)"));
		assertCVMEquals('a', eval("(char 97)"));
		assertEquals(97L, evalL("(long \\a)"));
		assertSame(CVMLong.create(1), eval("(byte 1)"));
	}

	@Test
	public void testBadStringCast() {
		assertCastError(step("(inc (str 1))"));
	}

	public static void doNumberTests(ANumeric a) {
		if (a instanceof AInteger) {
			doIntegerTests((AInteger) a);
		} else if (a instanceof CVMDouble) {
			doDoubleTests((CVMDouble) a);
		} else {
			fail("Unrecognised numeric value: "+a);
		}
	}
	
	public static void doIntegerTests(AInteger a) {
		assertEquals(a.abs(),RT.multiply(a.signum(),a));
		
		doGenericNumberTests(a);
	}
	
	public static void doDoubleTests(CVMDouble a) {
		assertEquals(a.abs(),RT.multiply(a.signum(),a));
		
		doGenericNumberTests(a);
	}
	
	public static void doGenericNumberTests(ANumeric a) {
		ObjectsTest.doAnyValueTests(a);
	}

}
