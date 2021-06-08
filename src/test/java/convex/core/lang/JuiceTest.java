package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;

/**
 * Tests for expected juice costs
 * 
 * These are not your regular example based tests. These are handcrafted,
 * artisinal juice tests.
 */
public class JuiceTest extends ACVMTest {

	public JuiceTest() {
		super(TestState.STATE);
	}
	
	private long JUICE = context().getJuice();

	/**
	 * Compute the precise juice consumed by executing the compiled source code
	 * (i.e. this excludes the code of expansion+compilation).
	 * 
	 * @param source
	 * @return Juice consumed
	 */
	public long juice(String source) {
		ACell form = Reader.read(source);
		AOp<?> op = context().expandCompile(form).getResult();
		Context<?> jctx = context().execute(op);
		return JUICE - jctx.getJuice();
	}

	/**
	 * Compute the precise juice consumed by compiling the source code (i.e. the
	 * cost of expand+compilation).
	 * 
	 * @param source
	 * @return Juice consumed
	 */
	public long compileJuice(String source) {
		ACell form = Reader.read(source);
		Context<?> jctx = context().expandCompile(form);
		return JUICE - jctx.getJuice();
	}

	/**
	 * Compute the precise juice consumed by expanding the source code (i.e. the
	 * cost of initial expander execution).
	 * 
	 * @param source
	 * @return Juice consumed
	 */
	public long expandJuice(String source) {
		ACell form = Reader.read(source);
		Context<?> jctx = context().invoke(Core.INITIAL_EXPANDER,form, Core.INITIAL_EXPANDER);
		return JUICE - jctx.getJuice();
	}

	/**
	 * Returns the difference in juice consumed between two sources
	 * 
	 * @param a
	 * @param b
	 * @return Difference in juice consumed
	 */
	public long juiceDiff(String a, String b) {
		return juice(b) - juice(a);
	}

	@Test
	public void testSimpleValues() {
		assertEquals(Juice.CONSTANT, juice("1"));
		assertEquals(Juice.LOOKUP_SYM, juice("count"));
		assertEquals(Juice.DO, juice("(do)"));
	}

	@Test
	public void testFunctionCalls() {
		assertEquals(Juice.LOOKUP_SYM + Juice.EQUALS, juice("(=)"));
	}

	@Test
	public void testCompileJuice() {
		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT, compileJuice("1"));
		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT, compileJuice("[]"));

		assertEquals(Juice.EXPAND_CONSTANT + Juice.COMPILE_LOOKUP, compileJuice("foobar"));
	}

	@Test
	public void testExpandJuice() {
		assertEquals(Juice.EXPAND_CONSTANT, expandJuice("1"));
		assertEquals(Juice.EXPAND_CONSTANT, expandJuice("[]"));
		assertEquals(Juice.EXPAND_SEQUENCE + Juice.EXPAND_CONSTANT * 4, expandJuice("(= 1 2 3)"));
		assertEquals(Juice.EXPAND_SEQUENCE + Juice.EXPAND_CONSTANT * 3, expandJuice("[1 2 3]")); // [1 2 3] -> (vector 1
																									// 2 3)
	}

	@Test
	public void testEval() {
		{// eval for a single constant
			long j = juice("(eval 1)");
			assertEquals((Juice.EVAL + Juice.LOOKUP_SYM + Juice.CONSTANT) + Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT
					+ Juice.CONSTANT, j);

			// expand list with symbol and number literal
			long je = expandJuice("(eval 1)");
			assertEquals((Juice.EXPAND_SEQUENCE + Juice.EXPAND_CONSTANT * 2), je);

			// compile node with constant and symbol lookup
			long jc = compileJuice("(eval 1)");
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
			long exParams = (Juice.LOOKUP_SYM + oneElemVectorJuice); // prepare call (lookup 'eval', build 1-vector arg)
			long exCompile = compileJuice("[1]"); // cost of compiling [1]
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
	public void testDef() {
		assertEquals(Juice.DEF + Juice.CONSTANT, juice("(def a 1)"));
	}

	@Test
	public void testReturn() {
		assertEquals(Juice.RETURN + Juice.CONSTANT + Juice.LOOKUP_SYM, juice("(return :foo)"));
	}

	@Test
	public void testHalt() {
		assertEquals(Juice.HALT + Juice.CONSTANT + Juice.LOOKUP_SYM, juice("(halt 123)"));
	}

	@Test
	public void testRollback() {
		assertEquals(Juice.ROLLBACK + Juice.CONSTANT + Juice.LOOKUP_SYM, juice("(rollback 123)"));
	}

	@Test
	public void testLoopIteration() {
		long j1 = juice("(loop [i 2] (cond (> i 0) (recur (dec i)) :end))");
		long j2 = juice("(loop [i 3] (cond (> i 0) (recur (dec i)) :end))");
		assertEquals(Juice.COND_OP + (Juice.LOOKUP_SYM * 3) + ((Juice.LOOKUP)*2) + Juice.CONSTANT * 1 + Juice.ARITHMETIC + Juice.NUMERIC_COMPARE
				+ Juice.RECUR, j2 - j1);
	}
}
