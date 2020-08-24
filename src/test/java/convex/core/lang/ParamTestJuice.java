package convex.core.lang;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.State;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

@RunWith(Parameterized.class)
public class ParamTestJuice {

	private static final long JUICE_EMPTY_MAP = (Juice.BUILD_DATA + Juice.LOOKUP_DYNAMIC); // consider: (hash-map)
	private static final long JUICE_IDENTITY_FN = (Juice.LAMBDA);

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { 
			    { "3", 3L, Juice.CONSTANT }, 
			    { "'()", Lists.empty(), Juice.CONSTANT },
				{ "{}", Maps.empty(), JUICE_EMPTY_MAP }, // (hash-map)
				{ "(hash-map)", Maps.empty(), JUICE_EMPTY_MAP }, // (hash-map)
				{ "(eval 1)", 1L,
						(Juice.EVAL + Juice.LOOKUP_DYNAMIC + Juice.CONSTANT) + Juice.EXPAND_CONSTANT + Juice.COMPILE_CONSTANT
								+ Juice.CONSTANT },
				{ "(do)", null, Juice.DO }, { "({} 0 1)", 1L, JUICE_EMPTY_MAP + Juice.CONSTANT * 2 },
				{ "(do (do :foo))", Keyword.create("foo"), Juice.DO * 2 + Juice.CONSTANT },
				{ "(let [])", null, Juice.LET }, { "(cond)", null, Juice.COND_OP },
				{ "(if 1 2 3)", 2L, Juice.COND_OP + 2 * Juice.CONSTANT },
				{ "(fn [x] x)", eval("(fn [x] x)").getResult(), JUICE_IDENTITY_FN },
				{ "(do (def a 3) a)", 3L, Juice.DO + Juice.CONSTANT + Juice.LOOKUP_DYNAMIC + Juice.DEF },
				{ "(do (let [a 1] (def f (fn [] a))) (f))", 1L,
						Juice.DO + Juice.LET + Juice.CONSTANT * 1 + Juice.LOOKUP_DYNAMIC + Juice.LOOKUP+ JUICE_IDENTITY_FN
								+ Juice.DEF },
				{ "(let [a 1] a)", 1L, Juice.LET + Juice.LOOKUP + Juice.CONSTANT }, { "~(+ 1 2)", 3L, Juice.CONSTANT }, // compiler
																														// executes
																														// +
																														// in
																														// advance,
																														// so
																														// this
																														// is
																														// constant
				{ "*depth*", 1L, Juice.LOOKUP_DYNAMIC },
				{ "(= true true)", true, (1 * Juice.LOOKUP_DYNAMIC) + (2 * Juice.CONSTANT) + Juice.EQUALS } });
	}

	private String source;
	private long expectedJuice;
	private Object expectedResult;

	public ParamTestJuice(String source, Object expectedResult, Long expectedJuice) {
		this.source = source;
		this.expectedJuice = expectedJuice;
		this.expectedResult = expectedResult;
	}

	private static final State INITIAL = TestState.INITIAL;
	private static final long INITIAL_JUICE = 10000;
	private static final Context<?> INITIAL_CONTEXT;

	static {
		try {
			INITIAL_CONTEXT = Context.createInitial(INITIAL, TestState.HERO, INITIAL_JUICE);
		} catch (Throwable e) {
			throw new Error(e);
		}
	}

	public static <T> AOp<T> compile(String source) {
		try {
			Context<?> c = INITIAL_CONTEXT;
			AOp<T> op = TestState.compile(c, source);
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static <T> Context<T> eval(String source) {
		try {
			Context<?> c = INITIAL_CONTEXT;
			AOp<T> op = TestState.compile(c, source);
			Context<T> rc = c.execute(op);
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
		Context<?> c = eval(source);

		Object result = c.getResult();
		assertEquals(expectedResult, result);

		long juiceUsed = INITIAL_JUICE - c.getJuice();
		assertEquals(expectedJuice, juiceUsed);

	}
}
