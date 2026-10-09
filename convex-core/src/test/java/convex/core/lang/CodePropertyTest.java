package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Random;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.exceptions.ParseException;
import convex.core.init.InitTest;
import convex.test.generators.FormGen;

@RunWith(JUnitQuickcheck.class)
public class CodePropertyTest {

	@Property
	public void testExpand(@From(FormGen.class) ACell form) {
		Context ctx = Context.create(TestState.STATE, InitTest.HERO);
		ctx = ctx.expand(form);
		assertEquals(0,ctx.getDepth());

		if (!ctx.isExceptional()) {
			ACell expObject=ctx.getResult();
			// Expansion can return any form, including an unchanged atom (CAD009).
			ctx=ctx.compile(expObject);
			assertEquals(0,ctx.getDepth());

			if (!ctx.isExceptional()) {
				ACell compObject=ctx.getResult();
				assertInstanceOf(AOp.class,compObject);

				ctx=ctx.execute((AOp<?>) compObject);
				assertEquals(0,ctx.getDepth());
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
