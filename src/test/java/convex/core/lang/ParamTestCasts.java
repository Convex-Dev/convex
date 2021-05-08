package convex.core.lang;

import static convex.core.lang.TestState.eval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.test.Samples;

public class ParamTestCasts {

	public final ACell[] values = new ACell[] {
			CVMLong.ZERO,
			CVMLong.ONE,
			CVMLong.MAX_VALUE,
			CVMLong.MIN_VALUE,
			CVMDouble.NaN,
			CVMDouble.POSITIVE_INFINITY,
			CVMDouble.NEGATIVE_INFINITY,
			CVMDouble.ZERO,
			Strings.EMPTY,
			Strings.create("foobar"),
			Samples.ACCOUNT_KEY,
			Samples.BAD_HASH,
			Samples.INT_SET_300,
			Samples.INT_VECTOR_23,
			Samples.LONG_MAP_100,
			null		
	};


	@SuppressWarnings("unchecked")
	@ParameterizedTest
	@ValueSource(strings = { "long","boolean","double","byte","char","blob","str","vec","set","blob","address"})
	public void testIdempotent(String name) {
		ACell fn=eval(name);
		
		assertTrue(fn instanceof AFn);
		
		Context<ACell> ctx=TestState.INITIAL_CONTEXT.fork();
		
		for (ACell x: values) {
			ACell[] args= new ACell[] {x};
			Context<ACell> r = ctx.fork().invoke((IFn<ACell>)fn, args);
			if (r.isExceptional()) {
				ACell code=r.getExceptional().getCode();
				assertTrue(Sets.of(ErrorCodes.CAST,ErrorCodes.ARGUMENT).contains(code));
			} else {
				ACell result=r.getResult();
				ACell result2=r.invoke((IFn<ACell>)fn, args).getResult();
				assertEquals(result,result2);
			}
		}
	}
}
