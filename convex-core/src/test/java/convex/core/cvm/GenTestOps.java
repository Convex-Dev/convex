package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.ACell;
import convex.core.init.InitTest;
import convex.core.lang.OpsTest;
import convex.test.generators.FormGen;
import convex.test.generators.OpGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestOps {
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void testOpExecution(@From(OpGen.class) AOp op) {
		// A context should be able to execute any valid Op without throwing
		Context c=Context.create(InitTest.STATE);
		long initialJuice=c.getJuiceUsed();
		assertEquals(0,initialJuice);
		c=c.execute(op);
		assertEquals(0,c.getDepth());
		
		assertTrue(c.getJuiceUsed()>initialJuice);
	
		OpsTest.doOpTest(op);
		
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testCompile(@From(FormGen.class) ACell form) {
		Context c=Context.create(InitTest.STATE);
		c=c.compile(form);
		if (c.isExceptional()) {
			// can easily happen, invalid syntax etc
		} else {
			AOp op=Ops.ensureOp(c.getResult());
			assertNotNull(op);
			OpsTest.doOpTest(op);
		}
	}


}
