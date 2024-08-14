package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Keywords;
import convex.core.data.prim.CVMLong;
import static convex.test.Assertions.*;

/**
 * Tests for Exception handling
 */
public class ExceptionTest extends ACVMTest {

	@Test public void testBasicTry() {
		assertEquals(CVMLong.ONE,eval("(try (fail :foo) 1)"));
		assertEquals(CVMLong.ONE,eval("(try 1 (fail :foo) 3)"));
		
		// Result is error code of previous failure
		assertEquals(Keywords.FOO,eval("(try (fail :foo :bar) *result*)"));
	}
	
	@Test public void testJuiceFailure() {
		Context ctx=context().withJuiceLimit(1000);
		ctx=step(ctx,"(try (loop [] (recur)) 1)");
		assertJuiceError(ctx);
	}
}
