package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;

import convex.core.data.ACell;
import convex.core.data.Syntax;
import convex.core.exceptions.ParseException;
import convex.core.init.InitTest;
import convex.test.generators.FormGen;

public class GenTestCode {

	@Property
	public void testExpand(@From(FormGen.class) ACell form) {
		Context ctx = Context.create(TestState.STATE, InitTest.HERO);
		ctx = ctx.expand(form);

		if (!ctx.isExceptional()) {
			ACell expObject=ctx.getResult();
			assertTrue(expObject instanceof Syntax);

			ctx=ctx.compile((Syntax) expObject);

			if (!ctx.isExceptional()) {
				ACell compObject=ctx.getResult();
				assertTrue(compObject instanceof AOp);

				ctx=ctx.execute((AOp<?>) compObject);
			}
		}

		String s=RT.toString(form);
		doMutateTest(s);
	}


	@SuppressWarnings("unused")
	public void doMutateTest(String original) {
		StringBuffer sb=new StringBuffer(original);
		Random r=new Random(original.hashCode());

		int n=r.nextInt(3);
		switch (n) {
			case 0: sb.deleteCharAt(r.nextInt(sb.length())); break;
			case 1: sb.insert(r.nextInt(sb.length()+1),sb.charAt(r.nextInt(sb.length()))); break;
			case 2: sb.setCharAt(r.nextInt(sb.length()),sb.charAt(r.nextInt(sb.length()))); break;
			default:
		}

		try {
			String source=sb.toString();
			ACell newForm=Reader.read(source);
			Syntax newSyntax=Reader.readSyntax(source);
		} catch (ParseException p) {
			// OK, we broken the string
		}
	}
}
