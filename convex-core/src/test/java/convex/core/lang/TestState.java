package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.ops.Special;
import convex.core.util.Utils;

/**
 * Class for building and testing a State for the unit test suite.
 *
 * Includes example smart contracts.
 */
public class TestState {
	public static final int NUM_CONTRACTS = 5;

	public static final Address[] CONTRACTS = new Address[NUM_CONTRACTS];

	/**
	 * A test state set up with a few accounts
	 */
	public static final State STATE;


	static {
		State s = InitTest.STATE;
		Context ctx = Context.create(s, InitTest.HERO);
		for (int i = 0; i < NUM_CONTRACTS; i++) {
			// Construct code for each contract
			ACell contractCode = Reader.read(
					"(do " + "(def my-data nil)" + "(defn write ^{:callable true} [x] (def my-data x)) "
							+ "(defn read ^{:callable true} [] my-data)" + "(defn who-called-me ^{:callable true} [] *caller*)"
							+ "(defn my-address ^{:callable true} [] *address*)" + "(defn my-number ^{:callable true} [] "+i+")" + "(defn foo ^{:callable true} [] :bar))");

			ctx = ctx.deploy(contractCode);
			CONTRACTS[i] = (Address) ctx.getResult();
		}

		s= ctx.getState();
		STATE = s;
		CONTEXT = Context.create(STATE, InitTest.HERO);
	}

	/**
	 * Initial juice for TestState.INITIAL_CONTEXT
	 */
	public static final long INITIAL_JUICE = Constants.MAX_TRANSACTION_JUICE;

	/**
	 * Initial juice price
	 */
	public static final CVMLong JUICE_PRICE = STATE.getJuicePrice();

	/**
	 * A test context set up with a few accounts
	 */
	public static final Context CONTEXT;



	/**
	 * Total funds in the test state, minus those subtracted for juice in the
	 * initial context
	 */
	public static final Long TOTAL_FUNDS = Constants.MAX_SUPPLY;




	@SuppressWarnings("unchecked")
	static <T extends ACell> AOp<T> compile(Context c, String source) {
		c=c.fork();
		try {
			ACell form = Reader.read(source);
			AOp<T> op = (AOp<T>) c.expandCompile(form).getResult();
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static <T extends ACell> T eval(Context c, String source) {
		c=c.fork();
		try {
			AOp<T> op = compile(c, source);
			Context rc = c.run(op);
			return rc.getResult();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	// Deploy actor code directly into a Context
	public static Context deploy(Context ctx,String actorResource) {
		String source;
		try {
			source = Utils.readResourceAsString(actorResource);
			ACell contractCode=Reader.read(source);
			ctx=ctx.deploy(contractCode);
		} catch (IOException e) {
			throw new RuntimeException("IO Error setting up testing state",e);
		}
		return ctx;
	}

	@Test
	public void testInitial() {
		Context ctx = Context.create(STATE,InitTest.HERO);
		State s = ctx.getState();
		assertEquals(STATE, s);
		assertSame(Core.COUNT, ctx.lookup(Symbols.COUNT).getResult());

		assertCVMEquals(Special.get("*timestamp*"), ctx.lookup(Symbols.STAR_TIMESTAMP).getResult());

		assertCVMEquals(Constants.INITIAL_TIMESTAMP, s.getTimestamp());
	}

	@Test
	public void testContractCall() {
		Context ctx0 = Context.create(STATE, InitTest.HERO);
		Address TARGET = CONTRACTS[0];
		ctx0 = ctx0.exec(compile(ctx0, "(def target " + TARGET + ")"));
		ctx0 = ctx0.exec(compile(ctx0, "(def hero *address*)"));
		final Context ctx = ctx0;

		assertEquals(InitTest.HERO, ctx.lookup(Symbols.HERO).getResult());
		assertEquals(Keyword.create("bar"), eval(ctx, "(call target (foo))"));
		assertEquals(InitTest.HERO, eval(ctx, "(call target (who-called-me))"));
		assertEquals(TARGET, eval(ctx, "(call target (my-address))"));

		assertEquals(0L, evalL(ctx, "(call target (my-number))"));

		assertStateError(TestState.step(ctx, "(call target (missing-function))"));
	}

	public static boolean evalB(String source) {
		return ((CVMBool)eval(source)).booleanValue();
	}

	public static boolean evalB(Context ctx, String source) {
		return ((CVMBool)eval(ctx, source)).booleanValue();
	}

	public static double evalD(Context ctx, String source) {
		ACell result=eval(ctx,source);
		CVMDouble d=RT.castDouble(result);
		if (d==null) throw new ClassCastException("Expected Double, but got: "+RT.getType(result));
		return d.doubleValue();
	}

	public static double evalD(String source) {
		return evalD(CONTEXT,source);
	}

	public static long evalL(Context ctx, String source) {
		ACell result=eval(ctx,source);
		CVMLong d=RT.castLong(result);
		if (d==null) throw new ClassCastException("Expected Long, but got: "+RT.getType(result));
		return d.longValue();
	}

	public static long evalL(String source) {
		return evalL(CONTEXT,source);
	}

	public static String evalS(String source) {
		return eval(source).toString();
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> T eval(String source) {
		return (T) step(source).getResult();
	}

	public static Context step(String source) {
		return step(CONTEXT, source);
	}

	/**
	 * Steps execution in a new forked Context
	 * @param ctx Initial context to fork
	 * @param source Source to execute
	 * @return New forked context containing step result
	 */
	public static Context step(Context ctx, String source) {
		// Compile form in forked context
		Context cctx=ctx.fork();
		ACell form = Reader.read(source);
		cctx = cctx.expandCompile(form);
		if (cctx.isExceptional()) return cctx;
		AOp<ACell> op = cctx.getResult();

		// Run form in separate forked context to get result context
		Context rctx = ctx.fork();
		rctx= rctx.run(op);
		assert(rctx.getDepth()==0):"Invalid depth after step: "+rctx.getDepth();
		return rctx;
	}

	/**
	 * Runs an execution step as a different address. Returns value after restoring
	 * the original address.
	 * @param address Address to run as
	 * @param c Initial Context. Will not be modified.
	 * @param source Source form to execute
	 * @return Updated context
	 */
	public static Context stepAs(Address address, Context c, String source) {
		Context rc = Context.create(c.getState(), address);
		rc = step(rc, source);
		return Context.create(rc.getState(), c.getAddress()).withValue(rc.getValue());
	}

	@Test public void testStateSetup() {
		assertEquals(0,CONTEXT.getDepth());
		assertFalse(CONTEXT.isExceptional());
		assertNull(CONTEXT.getResult());
		assertEquals(TestState.TOTAL_FUNDS, STATE.computeTotalBalance());

	}

	public static void main(String[] args) {
		System.out.println(Utils.print(STATE));
	}

}
