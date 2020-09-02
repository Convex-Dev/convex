package convex.core.lang;

import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.Init;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.Keyword;
import convex.core.util.Utils;

/**
 * Class for building and testing a State for the unit test suite.
 * 
 * Includes example smart contracts.
 */
public class TestState {
	public static final int NUM_CONTRACTS = 5;

	public static final Address HERO = Init.HERO;
	public static final AKeyPair HERO_PAIR = Init.KEYPAIRS[Init.NUM_PEERS + 0];

	public static final Address VILLAIN = Init.VILLAIN;
	public static final AKeyPair VILLAIN_PAIR = Init.KEYPAIRS[Init.NUM_PEERS + 1];

	public static final Address[] CONTRACTS = new Address[NUM_CONTRACTS];

	/**
	 * A test state set up with a few accounts
	 */
	public static final State INITIAL = createInitialState();

	/**
	 * Initial juice for TestState.INITIAL_CONTEXT
	 */
	public static final long INITIAL_JUICE = Constants.MAX_TRANSACTION_JUICE;

	/**
	 * Initial juice price
	 */
	public static final long JUICE_PRICE = INITIAL.getJuicePrice();

	/**
	 * A test context set up with a few accounts
	 */
	public static final Context<?> INITIAL_CONTEXT;

	/**
	 * Balance of hero's account before spending any juice / funds
	 */
	public static final long HERO_BALANCE = INITIAL.getAccounts().get(HERO).getBalance().getValue();

	/**
	 * Balance of hero's account before spending any juice / funds
	 */
	public static final long VILLAIN_BALANCE = INITIAL.getAccounts().get(VILLAIN).getBalance().getValue();

	/**
	 * Total funds in the test state, minus those subtracted for juice in the
	 * initial context
	 */
	public static final Long TOTAL_FUNDS = Amount.MAX_AMOUNT;

	static {
		try {
			INITIAL_CONTEXT = Context.createFake(INITIAL, TestState.HERO);
		} catch (Throwable e) {
			e.printStackTrace();
			throw new Error(e);
		}
	}

	@SuppressWarnings("unchecked")
	static <T> AOp<T> compile(Context<?> c, String source) {
		try {
			Object form = Reader.read(source);
			AOp<T> op = (AOp<T>) c.expandCompile(form).getResult();
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static <T> T eval(Context<?> c, String source) {
		try {
			AOp<T> op = compile(c, source);
			Context<T> rc = c.execute(op);
			return rc.getResult();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	private static State createInitialState() {
		try {
			State s = Init.STATE;
			Context<?> ctx = Context.createFake(s, HERO);
			for (int i = 0; i < NUM_CONTRACTS; i++) {
				// Construct code for each contract
				Object contractCode = Reader.read(
						"(do " + "(def my-data nil)" + "(defn write [x] (def my-data x)) "
								+ "(defn read [] my-data)" + "(defn who-called-me [] *caller*)"
								+ "(defn my-address [] *address*)" + "(defn my-number [] "+i+")" + "(defn foo [] :bar)"
								+ "(export write read who-called-me my-address my-number foo)" + ")");

				ctx = ctx.deployActor(contractCode,true);
				CONTRACTS[i] = (Address) ctx.getResult();
			}
			return ctx.getState();
		} catch (Throwable t) {
			t.printStackTrace();
			throw new Error(t);
		}
	}

	@Test
	public void testInitial() {
		Context<?> ctx = Context.createFake(INITIAL);
		State s = ctx.getState();
		assertEquals(INITIAL, s);
		assertEquals(Init.HERO,ctx.computeSpecial(Symbols.STAR_ADDRESS).getResult());
		assertSame(Core.COUNT, ctx.lookup(Symbols.COUNT).getResult());
		assertEquals(Constants.INITIAL_TIMESTAMP, (long) ctx.lookup(Symbols.STAR_TIMESTAMP).getResult());
		assertEquals(Constants.INITIAL_TIMESTAMP, s.getTimeStamp());
	}

	@Test
	public void testContractCall() {
		Context<?> ctx0 = Context.createFake(INITIAL, HERO);
		Address TARGET = CONTRACTS[0];
		ctx0 = ctx0.execute(compile(ctx0, "(def target (address \"" + TARGET.toHexString() + "\"))"));
		ctx0 = ctx0.execute(compile(ctx0, "(def hero *address*)"));
		final Context<?> ctx = ctx0;

		assertEquals(HERO, ctx.lookup(Symbols.HERO).getResult());
		assertEquals(Keyword.create("bar"), eval(ctx, "(call target (foo))"));
		assertEquals(HERO, eval(ctx, "(call target (who-called-me))"));
		assertEquals(TARGET, eval(ctx, "(call target (my-address))"));

		assertEquals(0L, (long) eval(ctx, "(call target (my-number))"));

		assertStateError(TestState.step(ctx, "(call target (missing-function))"));
	}

	public static boolean evalB(String source) {
		return (boolean) eval(source);
	}

	public static boolean evalB(Context<?> ctx, String source) {
		return (boolean) eval(ctx, source);
	}

	public static double evalD(Context<?> ctx, String source) {
		return (double) eval(ctx, source);
	}

	public static long evalL(Context<?> ctx, String source) {
		return (long) eval(ctx, source);
	}

	public static long evalL(String source) {
		return (long) eval(source);
	}

	@SuppressWarnings("unchecked")
	public static <T> T eval(String source) {
		return (T) step(source).getResult();
	}

	public static <T> Context<T> step(String source) {
		return step(INITIAL_CONTEXT, source);
	}

	@SuppressWarnings("unchecked")
	public static <T> Context<T> step(Context<?> ctx, String source) {
		Object form = Reader.read(source);
		Context<AOp<Object>> cctx = ctx.expandCompile(form);
		if (cctx.isExceptional()) return (Context<T>) cctx;

		AOp<Object> op = cctx.getResult();

		Context<T> rctx = (Context<T>) ctx.run(op);
		return rctx;
	}

	/**
	 * Runs an execution step as a different address. Returns value after restoring
	 * the original address.
	 */
	public static <T> Context<T> stepAs(String address, Context<?> ctx, String source) {
		try {
			return stepAs(RT.address(address), ctx, source);
		} catch (Exception e) {
			throw new Error(e);
		}
	}

	/**
	 * Runs an execution step as a different address. Returns value after restoring
	 * the original address.
	 */
	@SuppressWarnings("unchecked")
	public static <T> Context<T> stepAs(Address address, Context<?> c, String source) {
		Context<?> rc = Context.createFake(c.getState(), address);
		rc = step(rc, source);
		return (Context<T>) Context.createFake(rc.getState(), c.getAddress()).withResult(rc.getValue());
	}

	public static void main(String[] args) {
		System.out.println(Utils.ednString(INITIAL));
	}
}
