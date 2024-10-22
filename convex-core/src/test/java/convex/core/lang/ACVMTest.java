package convex.core.lang;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.init.BaseTest;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.core.util.Utils;

import static convex.test.Assertions.*;

/**
 * Base class for CVM tests that work from a given initial State and Context.
 *
 * Provides utility functions for CVM code execution.
 */
public abstract class ACVMTest {

	protected final State INITIAL;
	protected final Context CONTEXT;
	protected final long INITIAL_JUICE;

	/**
	 * Address of the HERO, equal to the genesis address
	 */
	protected final Address HERO;

	/**
	 * Address of the villain: has compromised peer at index 1 (i.e. the second
	 * peer)
	 */
	protected final Address VILLAIN;

	/**
	 * Balance of hero's account before spending any juice / funds
	 */
	public final long HERO_BALANCE;

	/**
	 * Balance of villain's account before spending any juice / funds
	 */
	public final long VILLAIN_BALANCE;

	/**
	 * Constructor using a specified Genesis State
	 * 
	 * @param genesis Genesis State to use for this CVM test
	 */
	protected ACVMTest(State genesis) {
		Context c = Context.create(genesis, Init.GENESIS_ADDRESS);
		HERO = BaseTest.HERO;
		VILLAIN = BaseTest.VILLAIN;
		INITIAL_JUICE = c.getJuiceAvailable();
		HERO_BALANCE = c.getAccountStatus(HERO).getBalance();
		VILLAIN_BALANCE = c.getAccountStatus(VILLAIN).getBalance();
		c=buildContext(c);
		if (c.isError()) throw new Error("Error initialising context: "+c.getExceptional());
		c=c.withJuice(0); // reset juice used
		this.INITIAL=c.getState();
		this.CONTEXT=c;
	}

	/**
	 * Default Constructor uses standard testing Genesis State
	 */
	protected ACVMTest() {
		this(InitTest.STATE);
	}

	protected Context context() {
		return CONTEXT.fork();
	}
	
	/**
	 * Builds the base Context for this test class instance. Subclasses may override
	 * to generate a separate context
	 * @param ctx Context to modify
	 * @return
	 */
	protected Context buildContext(Context ctx) {
		return ctx;
	}

	/**
	 * Steps execution in a new forked Context. Prefer `exec` to assert success. 
	 * 
	 * @param ctx    Initial context to fork
	 * @param source Source form to read
	 * @return New forked context containing step result (may be exceptional)
	 */
	public static Context step(Context ctx, String source) {
		ACell form = Reader.read(source);
		return step(ctx, form);
	}

	/**
	 * Steps execution in a new forked Context
	 * 
	 * @param ctx  Initial context to fork
	 * @param form Form to compile and execute execute
	 * @return New forked context containing step result
	 */
	public static Context step(Context ctx, ACell form) {
		// Run form in separate forked context to get result context
		Context rctx = ctx.fork();
		rctx = (Context) rctx.run(form);
		assert (rctx.getDepth() == 0) : "Invalid depth after step: " + rctx.getDepth();
		return rctx;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> AOp<T> compile(Context c, String source) {
		c = c.fork();
		try {
			ACell form = Reader.read(source);
			AOp<T> op = (AOp<T>) c.expandCompile(form).getResult();
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static <T extends ACell> T read(String source) {
		return Reader.read(source);
	}

	/**
	 * Runs an execution step as a different address. Returns value after restoring
	 * the original address.
	 * 
	 * @param address Address to run as
	 * @param c       Initial Context. Will not be modified.
	 * @param source  Source form
	 * @return Updates Context
	 */
	public static Context stepAs(Address address, Context c, String source) {
		Context rc = Context.create(c.getState(), address);
		rc = step(rc, source);
		return Context.create(rc.getState(), c.getAddress()).withValue(rc.getValue());
	}

	public Address evalA(String source) {
		return evalA(CONTEXT, source);
	}

	public static Address evalA(Context ctx, String source) {
		return eval(ctx, source);

	}

	public boolean evalB(String source) {
		return ((CVMBool) eval(source)).booleanValue();
	}
	
	public boolean evalB(ACell code) {
		return ((CVMBool) eval(code)).booleanValue();
	}

	public static boolean evalB(Context ctx, String source) {
		return ((CVMBool) eval(ctx, source)).booleanValue();
	}

	public static double evalD(Context ctx, String source) {
		ACell result = eval(ctx, source);
		CVMDouble d = RT.castDouble(result);
		if (d == null)
			throw new ClassCastException("Expected Double, but got: " + RT.getType(result));
		return d.doubleValue();
	}

	public double evalD(String source) {
		return evalD(CONTEXT, source);
	}

	public static long evalL(Context ctx, String source) {
		ACell result = eval(ctx, source);
		CVMLong d = RT.ensureLong(result);
		if (d == null)
			throw new ClassCastException("Expected Long, but got: " + RT.getType(result));
		return d.longValue();
	}

	/**
	 * Evaluates source and coerces to a long value
	 * @param source Code to execute
	 * @return Result coerced to Java long primitive
	 */
	public long evalL(String source) {
		return evalL(CONTEXT, source);
	}

	/**
	 * Executes code in the given context and converts to a String
	 * @param source Code to execute
	 * @return Result of execution
	 */
	public String evalS(String source) {
		return Utils.toString(eval(source));
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T eval(String source) {
		return (T) step(source).getResult();
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T eval(ACell form) {
		return (T) step(context(), form).getResult();
	}

	/**
	 * Executes code in the given context, asserting no errors
	 * @param source Code to execute
	 * @return Result of execution
	 */
	public static <T extends ACell> T eval(Context c, String source) {
		Context rc = step(c, source);
		return rc.getResult();
	}

	protected Context step(String source) {
		return step(context(), source);
	}
	
	/**
	 * Executes code in the given context, asserting no errors
	 * @param ctx Context in which to execute
	 * @param source Code to execute
	 * @return
	 */
	protected static Context exec(Context ctx, String source) {
		ctx=step(ctx, source);
		assertNotError(ctx);
		return ctx;
	}

	@SuppressWarnings("unchecked")
	public static <T extends AOp<?>> T comp(ACell form, Context context) {
		context = context.fork(); // fork to avoid corrupting original context
		AOp<?> code = context.expandCompile(form).getResult();
		return (T) code;
	}

	public static <T extends AOp<?>> T comp(String source, Context context) {
		return comp(Reader.read(source), context);
	}

	/**
	 * Compiles source code to a CVM Op
	 * 
	 * @param <T> Type of compiled Op
	 * @param source Source to compile
	 * @return CVM Op
	 */
	public <T extends AOp<?>> T comp(String source) {
		return comp(Reader.read(source), CONTEXT);
	}

	/**
	 * Returns the difference in juice consumed between two sources
	 * 
	 * @param a First example of source code
	 * @param b Second example of source code
	 * @return Difference in juice consumed
	 */
	public long juiceDiff(String a, String b) {
		return juice(b) - juice(a);
	}

	/**
	 * Compute the precise juice consumed by compiling the source code (i.e. the
	 * cost of expand+compilation).
	 * 
	 * @param source Source to expand and compile
	 * @return Juice consumed
	 */
	public long juiceCompile(String source) {
		ACell form = Reader.read(source);
		Context jctx = context().expandCompile(form);
		return jctx.getJuiceUsed()-CONTEXT.getJuiceUsed();
	}

	/**
	 * Compute the precise juice consumed by expanding the source code (i.e. the
	 * cost of initial expander execution).
	 * 
	 * @param source Source to expand
	 * @return Juice consumed
	 */
	public long juiceExpand(String source) {
		ACell form = Reader.read(source);
		Context jctx = context().invoke(Core.INITIAL_EXPANDER, form, Core.INITIAL_EXPANDER);
		return jctx.getJuiceUsed()-CONTEXT.getJuiceUsed();
	}

	/**
	 * Compute the precise juice consumed by executing the compiled source code
	 * (i.e. this excludes the code of expansion+compilation).
	 * 
	 * @param source Source code to evaluate
	 * @return Juice consumed
	 */
	public long juice(String source) {
		return juice(CONTEXT, source);
	}

	/**
	 * Compute the precise juice consumed by executing the compiled source code
	 * (i.e. this excludes the code of expansion+compilation).
	 * 
	 * @param ctx    Context in which to execute operation
	 * @param source Source code to evaluate
	 * @return Juice consumed
	 */
	public long juice(Context ctx, String source) {
		ACell form = Reader.read(source);
		AOp<?> op = ctx.fork().expandCompile(form).getResult();
		Context jctx = ctx.fork().execute(op);
		return jctx.getJuiceUsed()-ctx.getJuiceUsed();
	}

	/**
	 * Compiles source code to a CVM Op
	 * 
	 * @param <T> Type of compiled Op
	 * @param code Source code to compile as a form
	 * @return CVM Op
	 */
	public <T extends AOp<?>> T comp(ACell code) {
		return comp(code, CONTEXT);
	}

	public ACell expand(ACell form) {
		Context ctx = CONTEXT.fork();
		ACell expanded = ctx.expand(form).getResult();
		return expanded;
	}

	public ACell expand(String source) {
		ACell form = Reader.read(source);
		return expand(form);
	}

}
