package convex.core.lang;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;

import convex.core.Init;
import convex.generators.FormGen;

public class GenTestCode {

	@Property
	public void testExpand(@From(FormGen.class) Object form) {
		Context<?> ctx = Context.createFake(Init.STATE, Init.HERO);

		ctx = ctx.expand(form);
	}
}
