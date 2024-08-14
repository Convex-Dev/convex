package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.Test;

import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.impl.Fn;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Local;

public class FunctionTest {

	State STATE=State.EMPTY.addActor();
	
	@Test public void testFnApply() {
		Context ctx=Context.create(STATE);
		Fn<CVMLong> f0=Fn.create(Vectors.empty(), Constant.create(CVMLong.ONE));	
		assertEquals(CVMLong.ONE,f0.invoke(ctx, new ACell[0]).getResult());
		
		Fn<CVMLong> f1=Fn.create(Vectors.of(Symbols.FOO), Local.create(0));	
		assertEquals(CVMLong.ZERO,f1.invoke(ctx, new ACell[] {CVMLong.ZERO}).getResult());

	}
}
