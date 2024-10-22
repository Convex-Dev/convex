package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.ops.Constant;
import convex.core.data.ACell;

/**
 * Tests for pre-compilation
 */
public class PrecompileTest extends ACVMTest {

	@Test public void testPrecompile() {
		AOp<?> op=precompile("1");
		
		assertEquals(Constant.of(1),op);
		
	}

	private AOp<?> precompile(String string) {
		Context ctx=context();
		ACell form=Reader.read(string);
		
		ctx=ctx.compile(form);
		return ctx.getResult();
	}
}
