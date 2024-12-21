package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.init.InitTest;
import convex.test.generators.OpsGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestOps {
	
	@SuppressWarnings("rawtypes")
	@Property
	public void testOpExecution(@From(OpsGen.class) AOp op) {
		// A context should be able to execute any valid Op without throwing
		Context c=Context.create(InitTest.STATE);
		long initialJuice=c.getJuiceUsed();
		assertEquals(0,initialJuice);
		c=c.execute(op);
		assertEquals(0,c.getDepth());
		
		assertTrue(c.getJuiceUsed()>initialJuice);
		
	}


}
