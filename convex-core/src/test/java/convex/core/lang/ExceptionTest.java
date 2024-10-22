package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.data.Keywords;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import static convex.test.Assertions.*;

/**
 * Tests for Exception handling
 */
public class ExceptionTest extends ACVMTest {

	@Test public void testBasicTry() {
		assertEquals(CVMLong.ONE,eval("(try (fail :foo) 1)"));
		assertEquals(CVMLong.ONE,eval("(try 1 (fail :foo) 3)"));
		
		assertCastError(step("(try (int :foo))"));
		assertArityError(step("(try (transfer))"));
		
		assertNull(eval("(try)"));
		
		// Result is error code of previous failure
		assertEquals(Keywords.FOO,eval("(try (fail :foo :bar) *result*)"));
	}
	
	/**
	 * Failed try branches should be fully rolled back, but won't affect other successful state changes outside the branch
	 */
	@Test public void testTryRollbacks() {
		assertEquals(Vectors.of(1,2,3),eval("(do (def a 1) (def b 2) (def c 666) (try (do (def b 666) (fail)) (def c 3)) [a b c])"));
	}
	
	/**
	 * Juice failure should never be caught. Transaction is dead.
	 */
	@Test public void testJuiceFailure() {
		
		Context ctx=context().withJuiceLimit(1000);
		assertJuiceError(step(ctx,"(try (loop [] (recur)) 1)"));
		assertJuiceError(step(ctx,"(try ((fn [] (recur))) 1)"));
	}
	
	/**
	 * Halts are not caught
	 */
	@Test public void testHalt() {
		
		assertEquals(CVMLong.ONE,eval("(try (halt 1) (fail :boosh))"));
	}
	
	/**
	 * Roll-backs are not caught
	 */
	@Test public void testRollback() {
		assertEquals(CVMLong.ONE,eval("(try (rollback 1) (fail :boosh))"));
	}
	
	/**
	 * Returns are not caught
	 */
	@Test public void testReturn() {
		assertEquals(CVMLong.ONE,eval("((fn [] (try (fail :never) (return 1) 2)))"));
	}
}
