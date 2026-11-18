package convex.core.lang;

import static convex.core.lang.TestState.eval;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.ErrorCodes;
import convex.core.cvm.AFn;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.Sets;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.test.Samples;

public class CastsParamTest {

	public final ACell[] values = new ACell[] {
			CVMLong.ZERO,
			CVMLong.ONE,
			CVMLong.MAX_VALUE,
			CVMLong.MIN_VALUE,
			CVMDouble.NaN,
			CVMDouble.POSITIVE_INFINITY,
			CVMDouble.NEGATIVE_INFINITY,
			CVMDouble.ZERO,
			StringShort.EMPTY,
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
	@ValueSource(strings = { "long","boolean","double","byte","char","blob","str","vec","set","address"})
	public void testIdempotent(String name) {
		ACell namedFn=eval(name);
		
		assertTrue(namedFn instanceof AFn);
		AFn<ACell> fn=(AFn<ACell>)namedFn;
		
		Context ctx=TestState.CONTEXT.fork();
		
		for (ACell x: values) {
			String where=name+" applied to "+x;
			ACell[] args= new ACell[] {x};
			Context r = ctx.fork().invoke(fn, args);
			if (r.isExceptional()) {
				ACell code=r.getExceptional().getCode();
				assertTrue(Sets.of(ErrorCodes.CAST,ErrorCodes.ARGUMENT).contains(code),where);
			} else {
				ACell result=r.getResult();
				Context repeated=ctx.fork().invoke(fn,args);
				assertNotError(repeated);
				assertEquals(result,repeated.getResult(),"Repeatability: "+where);

				Context recast=ctx.fork().invoke(fn,new ACell[] {result});
				assertNotError(recast);
				assertEquals(result,recast.getResult(),"Idempotence: "+where);
			}
		}
	}
}
