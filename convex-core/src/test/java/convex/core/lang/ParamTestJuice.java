package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.exceptions.BadFormatException;
import convex.core.init.InitTest;
import convex.core.util.Utils;

@RunWith(Parameterized.class)
public class ParamTestJuice {

	private static final long JUICE_SYM_LOOKUP = Juice.LOOKUP_SYM;
	private static final long JUICE_EMPTY_MAP = Juice.CONSTANT; // consider: (hash-map) vs {}
	private static final long JUICE_IDENTITY_FN = (Juice.LAMBDA);

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] {
			    { "3", 3L, Juice.CONSTANT },
			    { "'()", Lists.empty(), Juice.CONSTANT },
				{ "{}", Maps.empty(), JUICE_EMPTY_MAP }, // {}
				{ "(hash-map)", Maps.empty(), (Juice.BUILD_DATA + Juice.CORE) }, // (hash-map)
				{ "(eval 1)", 1L,
						(Juice.EVAL + Juice.CORE + Juice.CONSTANT) + Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT
								+ Juice.CONSTANT },
				{ "(do)", null, Juice.DO }, { "({} 0 1)", 1L, JUICE_EMPTY_MAP + Juice.CONSTANT * 2 },
				{ "(do (do :foo))", Keyword.create("foo"), Juice.CONSTANT },
				{ "(let [])", null, Juice.LET }, { "(cond)", null, Juice.COND_OP },
				{ "(if 1 2 3)", 2L, Juice.COND_OP + 2 * Juice.CONSTANT },
				{ "(fn [x] x)", eval("(fn [x] x)").getResult(), JUICE_IDENTITY_FN },
				{ "(do (def a 3) a)", 3L, Juice.DO + Juice.CONSTANT + JUICE_SYM_LOOKUP + Juice.DEF },
				{ "(do (let [a 1] (def f (fn [] a))) (f))", 1L,
						Juice.DO + Juice.LET + Juice.CONSTANT * 1 + JUICE_SYM_LOOKUP + Juice.LOOKUP + JUICE_IDENTITY_FN
								+ Juice.DEF },
				{ "(let [a 1] a)", 1L, Juice.LET + Juice.LOOKUP + Juice.CONSTANT }, 
				// compiler executes + in advance, so this is constant in execution
				{ "~(+ 1 2)", 3L, Juice.CONSTANT }, 
				{ "*depth*", 0L, Juice.SPECIAL },
				{ "(= true true)", true, Juice.CORE+ (2 * Juice.CONSTANT) + Juice.EQUALS } });
	}

	private String source;
	private long expectedJuice;
	private Object expectedResult;

	public ParamTestJuice(String source, Object expectedResult, Long expectedJuice) {
		this.source = source;
		this.expectedJuice = expectedJuice;
		this.expectedResult = expectedResult;
	}

	private static final State INITIAL = TestState.STATE;
	private static final long INITIAL_JUICE = 10000;
	private static final Context INITIAL_CONTEXT;

	static {
		try {
			INITIAL_CONTEXT = Context.create(INITIAL, InitTest.HERO, INITIAL_JUICE);
		} catch (Throwable e) {
			throw new Error(e);
		}
	}

	public static <T extends ACell> AOp<T> compile(String source) {
		try {
			Context c = INITIAL_CONTEXT.fork();
			AOp<T> op = TestState.compile(c, source);
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static Context eval(String source) {
		try {
			Context c = INITIAL_CONTEXT.fork();
			AOp<?> op = TestState.compile(c, source);
			Context rc = c.fork().execute(op);
			return rc;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Test
	public void testOpRoundTrip() throws BadFormatException {
		AOp<?> op = compile(source);
		Blob b = Format.encodedBlob(op);
		AOp<?> op2 = Format.read(b);
		Blob b2 = Format.encodedBlob(op2);
		assertEquals(b, b2);
	}

	@Test
	public void testResultAndJuice() {
		Context c = eval(source);

		ACell result = c.getResult();
		assertCVMEquals(expectedResult, result);

		long juiceUsed = c.getJuiceUsed();
		assertEquals(expectedJuice, juiceUsed);

	}
}
