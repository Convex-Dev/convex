package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.cvm.ops.Cond;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Def;
import convex.core.cvm.ops.Do;
import convex.core.cvm.ops.Invoke;
import convex.core.cvm.ops.Lookup;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.lang.TestState;

public class OpsParamTest {

	private static final State INITIAL_STATE = TestState.STATE;

	public static Collection<Object[]> dataExamples() throws BadFormatException {
		return Arrays
				.asList(new Object[][] {
					    { "Constant", Constant.of(1L), RT.cvm(1L) },
						{ "Lookup", Do.create(Def.create("foo", Constant.of(13)),
								Lookup.create("foo")), RT.cvm(13) },
						{ "Def", Def.create("foo", Constant.createString("bar")), Strings.create("bar") },
						{ "Vector", Invoke.create("vector", Constant.createString("foo"), Constant.createString("bar")),
								Vectors.of(Strings.create("foo"), Strings.create("bar")) },

						{ "Do", Do.create(Constant.createString("foo"), Constant.createString("bar")), Strings.create("bar") },
						{ "Cond",
								Cond.create(Constant.of(CVMBool.TRUE), Constant.createString("truthy"),
										Constant.createString("falsey")),
								Strings.create("truthy") },
						{ "Def", Def.create("foo", Constant.of(1L)), 1L } });
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testExpectedResult(String label, AOp<?> op, Object expected) {
		long JUICE = 10000;
		Context c = Context.create(INITIAL_STATE, InitTest.HERO, JUICE);
		Context c2 = c.execute(op);

		assertCVMEquals(expected, c2.getResult());
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testCanonical(String label, AOp<?> op, Object expected) {
		assertTrue(op.isCanonical());
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testGeneric(String label, AOp<?> op, Object expected) throws InvalidDataException, ValidationException {
		ObjectsTest.doAnyValueTests(op);
	}
}
