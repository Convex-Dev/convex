package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.data.Address;

import static convex.test.Assertions.*;

/**
 * Tests for expected juice costs
 * 
 * These are not your regular example based tests. These are handcrafted,
 * artisinal juice tests.
 */
public class JuiceTest extends ACVMTest {

	@Test
	public void testSimpleValues() {
		assertEquals(Juice.CONSTANT, juice("1"));
		assertEquals(Juice.CORE, juice("count")); // core symbol lookup, might be static
	}
	
	@Test public void testDo() {
		// Special case, gets compiled to constant nil
		assertEquals(Juice.CONSTANT, juice("(do)"));
		
		assertEquals(Juice.DO+Juice.CONSTANT*2, juice("(do 1 2)"));
		
		// special case, single element do gets compiled to single op
		assertEquals(Juice.SPECIAL, juice("(do *result*)"));
	}
	
	@Test public void testTry() {
		// Special case, gets compiled to constant nil
		assertEquals(Juice.CONSTANT, juice("(try)"));
		
		// special case, single element try gets compiled to single op
		assertEquals(Juice.SPECIAL, juice("(try *balance*)"));
		
		// TRY cost gets applied for each forked execution required
		assertEquals(Juice.TRY+Juice.CONSTANT, juice("(try 1 2 3 4 5)"));

	}

	@Test
	public void testFunctionCalls() {
		assertEquals(Juice.CORE + Juice.EQUALS, juice("(=)"));
		assertEquals(Juice.CORE + Juice.EQUALS+ Juice.CONSTANT*2, juice("(= 1 1)"));
	}

	@Test
	public void testCompileJuice() {
		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT, juiceCompile("1"));
		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT, juiceCompile("[]"));

		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_LOOKUP_CORE, juiceCompile("if"));
		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_LOOKUP_UNDEFINED, juiceCompile("foobar"));
	}

	@Test
	public void testExpandJuice() {
		assertEquals(Juice.EXPAND_CONSTANT, juiceExpand("1"));
		assertEquals(Juice.EXPAND_CONSTANT, juiceExpand("[]"));
		assertEquals(Juice.EXPAND_SEQUENCE + Juice.EXPAND_CONSTANT * 4, juiceExpand("(= 1 2 3)"));
		assertEquals(Juice.EXPAND_SEQUENCE + Juice.EXPAND_CONSTANT * 3, juiceExpand("[1 2 3]")); // [1 2 3] -> (vector 1
																									// 2 3)
	}
	
	@Test
	public void testJuiceLimit() {
		Context ctx=context();
		Address D=VILLAIN;
		
		// Shouldn't be able to afford sending full balance
		assertJuiceError(step(ctx,"(transfer "+D+" *balance*)"));
	}

	@Test
	public void testEval() {
		{// eval for a single constant
			long j = juice("(eval 1)");
			assertEquals((Juice.EVAL + Juice.CORE + Juice.CONSTANT) + Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT
					+ Juice.CONSTANT, j);

			// expand list with symbol and number literal
			long je = juiceExpand("(eval 1)");
			assertEquals((Juice.EXPAND_SEQUENCE + Juice.EXPAND_CONSTANT * 2), je);

			// compile node with constant and symbol lookup
			long jc = juiceCompile("(eval 1)");
			assertEquals(je + (Juice.COMPILE_NODE + Juice.COMPILE_CONSTANT + Juice.COMPILE_LOOKUP), jc);
		}

		// Calculate cost of executing op to build a single element vector, need this
		// later
		long oneElemVectorJuice = juice("[1]");
		// (vector 1), where vector is a constant core function.
		assertEquals((Juice.CONSTANT + Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT + Juice.CONSTANT),
				oneElemVectorJuice);

		{// eval for a small vector
			long j = juice("(eval [1])");
			long exParams = (Juice.CORE + oneElemVectorJuice); // prepare call (lookup 'eval', build 1-vector arg)
			long exCompile = juiceCompile("[1]"); // cost of compiling [1]
			long exInvoke = (Juice.EVAL + oneElemVectorJuice); // cost of eval plus cost of running [1]
			assertEquals(exParams + exCompile + exInvoke, j);
		}

		{
			long jdiffSimple = juiceDiff("[1]", "[1 2]");
			assertEquals(Juice.BUILD_PER_ELEMENT + Juice.CONSTANT, jdiffSimple); // extra cost per element in execution

			long jdiff = juiceDiff("(eval [1])", "(eval [1 2])");

			// we pay +1 simple cost preparing args eval call, and +1 in ecexution phase.
			// One extra constant in expand and compile phase.
			assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT + jdiffSimple * 2, jdiff);
		}
	}

	@Test
	public void testBalanceAway() {
		assertJuiceError(step("(transfer #12 *balance*)"));
	}
	
	@Test
	public void testDef() {
		assertEquals(Juice.DEF + Juice.CONSTANT, juice("(def a 1)"));
	}
	
	@Test public void testLookup() {
		if (Constants.OPT_STATIC) {
			assertEquals(Juice.CONSTANT,juice("count"));
		} else {
			assertEquals(Juice.LOOKUP*3,juice("count"));
		}
		
		assertEquals(Juice.LOOKUP*3,juice("missing"));
		assertEquals(Juice.LOOKUP*3,juice("if"));
	}

	@Test
	public void testReturn() {
		assertEquals(Juice.RETURN + Juice.CORE + Juice.CONSTANT, juice("(return :foo)"));
	}

	@Test
	public void testHalt() {
		assertEquals(Juice.RETURN + Juice.CORE + Juice.CONSTANT, juice("(halt 123)"));
	}

	@Test
	public void testRollback() {
		assertEquals(Juice.RETURN + Juice.CORE + Juice.CONSTANT, juice("(rollback 123)"));
	}

	@Test
	public void testLoopIteration() {
		long j1 = juice("(loop [i 2] (cond (> i 0) (recur (dec i)) :end))");
		long j2 = juice("(loop [i 3] (cond (> i 0) (recur (dec i)) :end))");
		assertEquals(Juice.COND_OP + (Juice.CORE * 3) + ((Juice.LOOKUP)*2) + Juice.CONSTANT * 1 + Juice.ARITHMETIC + Juice.NUMERIC_COMPARE
				+ Juice.RECUR, j2 - j1);
	}
}
