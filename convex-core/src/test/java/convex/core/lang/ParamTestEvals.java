package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.exceptions.BadFormatException;
import convex.core.init.InitTest;
import convex.core.util.Utils;

@RunWith(Parameterized.class)
public class ParamTestEvals {

	private static final Context INITIAL_CONTEXT = TestState.CONTEXT.fork();

	private static final Address TEST_CONTRACT = TestState.CONTRACTS[0];

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] {
				{ "(do)", null },
				{ "(do (do :foo))", Keyword.create("foo") },
				{ "(do 1 2)", 2L },
				{ "(do 1 *result*)", 1L },
				{ "(do (do :foo) (do))", null },
				{ "*result*", null },
				{ "*origin*", InitTest.HERO },
				{ "*caller*", null },
				{ "*address*", InitTest.HERO },
				{ "(do 1 *result*)", 1L },

				{ "(call " + TEST_CONTRACT + " (my-address))", TEST_CONTRACT },
				{ "(call " + TEST_CONTRACT + " (foo))", Keyword.create("bar") },

				{ "(let [a (address " + TEST_CONTRACT + ")]" + "(call a (write :bar))" + "(call a (read)))",
						Keyword.create("bar") },

				{ "*depth*", 0L }, // *depth*
				{ "(do :foo *depth*)", 1L }, // do, *depth*
				{ "(let [a *depth*] a)", 1L }, // let, *depth*
				{ "(let [f (fn [] *depth*)] (f))", 2L }, // let, invoke, *depth*

				{ "(let [])", null }, { "(let [a 1])", null }, { "(let [a 1] a)", 1L },
				{ "(do (def a 2) (let [a 13] a))", 13L }, 
				{ "*juice*", 0 }, // Initial juice used
				{ "(- *juice* *juice*)", -Juice.SPECIAL },
				{ "((fn [a] a) 4)", 4L }, { "(do (def a 3) a)", 3L },
				{ "(do (let [a 1] (def f (fn [] a))) (f))", 1L }, { "1", 1L }, { "(not true)", false },
				{ "(= true true)", true } });
	}

	private String source;
	private Object expectedResult;

	public ParamTestEvals(String source, Object expectedResult) {
		this.source = source;
		this.expectedResult = expectedResult;
	}

	public <T extends ACell> AOp<T> compile(String source) {
		try {
			Context c = INITIAL_CONTEXT.fork();
			AOp<T> op = TestState.compile(c, source);
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public Context eval(AOp<?> op) {
		try {
			Context c = INITIAL_CONTEXT.fork();
			Context rc = c.execute(op);
			return rc;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public Context eval(String source) {
		try {
			Context c = INITIAL_CONTEXT.fork();
			AOp<?> op = TestState.compile(c, source);
			return eval(op);
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Test
	public void testOpRoundTrip() throws BadFormatException, IOException {
		AOp<?> op = compile(source);
		Blob b = Format.encodedBlob(op);
		Cells.persist(op); // persist to allow re-creation

		AOp<?> op2 = Format.read(b);
		Blob b2 = Format.encodedBlob(op2);
		assertEquals(b, b2);

		ACell result = eval(op2).getResult();
		assertCVMEquals(expectedResult, result);
	}

	@Test
	public void testResultAndJuice() {
		Context c = eval(source);

		ACell result = c.getResult();
		assertCVMEquals(expectedResult, result);
	}
}
