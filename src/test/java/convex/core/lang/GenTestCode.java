package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;

import convex.core.Init;
import convex.core.data.Syntax;
import convex.test.generators.FormGen;

public class GenTestCode {

	@Property
	public void testExpand(@From(FormGen.class) Object form) {
		Context<?> ctx = Context.createFake(Init.STATE, Init.HERO);
		ctx = ctx.expand(form);
		
		if (!ctx.isExceptional()) {
			Object expObject=ctx.getResult();
			assertTrue(expObject instanceof Syntax);

			ctx=ctx.compile((Syntax) expObject);
			
			if (!ctx.isExceptional()) {
				Object compObject=ctx.getResult();
				assertTrue(compObject instanceof AOp);
				
				ctx=ctx.execute((AOp<?>) compObject);
			}
		}
	}
}
