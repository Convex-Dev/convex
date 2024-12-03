package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.ops.Constant;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.ObjectsTest;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.ACVMTest;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.Fn;

public class FunctionTest extends ACVMTest {

	@Test public void testSimpleFn() throws BadFormatException {
		Fn<?> f=Fn.create(Vectors.empty(), Constant.TRUE);
		
		Context ctx=context();
		ctx=f.invoke(ctx, Cells.EMPTY_ARRAY);
		assertEquals(CVMBool.TRUE,ctx.getResult());
		
		Blob enc=f.getEncoding();
		assertTrue(Format.read(enc) instanceof Fn);
		
		doFunctionTest(f);
	}

	private void doFunctionTest(AClosure<?> f) {
		ObjectsTest.doAnyValueTests(f);
	}
}
