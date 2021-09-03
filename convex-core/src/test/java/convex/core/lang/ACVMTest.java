package convex.core.lang;

import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.core.util.Utils;

/**
 * Base class for CVM tests that work from a given initial state and context.
 *
 * Provides utility functions for CVM code execution.
 */
public abstract class ACVMTest {

    protected State INITIAL;
	private Context<?> CONTEXT;
	protected long INITIAL_JUICE;
	
	/**
	 * Address of the HERO, equal to the genesis address
	 */
	protected final Address HERO;
	
	/**
	 * Address of the villain: has compromised peer at index 1 (i.e. the second peer)
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
	 * @param genesis Genesis State to use for this CVM test
	 */
	protected ACVMTest(State genesis) {
		this.INITIAL=genesis;
		CONTEXT=Context.createFake(genesis,Init.GENESIS_ADDRESS);
		HERO=InitTest.HERO;
		VILLAIN=InitTest.VILLAIN;
		INITIAL_JUICE=CONTEXT.getJuice();
		HERO_BALANCE = INITIAL.getAccount(InitTest.HERO).getBalance();
		VILLAIN_BALANCE = INITIAL.getAccount(InitTest.VILLAIN).getBalance();
	}
	
	/**
	 * Default Constructor uses standard testing Genesis State
	 */
	protected ACVMTest() {
		this(InitTest.STATE);
	}

	@SuppressWarnings("unchecked")
	protected <T extends ACell> Context<T> context() {
		return (Context<T>) CONTEXT.fork();
	}

	/**
	 * Steps execution in a new forked Context
	 * @param <T> Type of result
	 * @param ctx Initial context to fork
	 * @param source Source form to read
	 * @return New forked context containing step result
	 */
	public <T extends ACell> Context<T> step(Context<?> ctx, String source) {
		ACell form = Reader.read(source);
		return step(ctx,form);
	}

	/**
	 * Steps execution in a new forked Context
	 * @param <T> Type of result
	 * @param ctx Initial context to fork
	 * @param form Form to compile and execute execute
	 * @return New forked context containing step result
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> Context<T> step(Context<?> ctx, ACell form) {
		// Run form in separate forked context to get result context
		Context<T> rctx = ctx.fork();
		rctx=(Context<T>) rctx.run(form);
		assert(rctx.getDepth()==0):"Invalid depth after step: "+rctx.getDepth();
		return rctx;
	}


	@SuppressWarnings("unchecked")
	public <T extends ACell> AOp<T> compile(Context<?> c, String source) {
		c=c.fork();
		try {
			ACell form = Reader.read(source);
			AOp<T> op = (AOp<T>) c.expandCompile(form).getResult();
			return op;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}



	public <T extends ACell> T read(String source) {
		return Reader.read(source);
	}

	/**
	 * Runs an execution step as a different address. Returns value after restoring
	 * the original address.
	 * @param address Address to run as
	 * @param c Initial Context. Will not be modified.
	 * @param source Source form
	 * @return Updates Context
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> Context<T> stepAs(Address address, Context<?> c, String source) {
		Context<?> rc = Context.createFake(c.getState(), address);
		rc = step(rc, source);
		return (Context<T>) Context.createFake(rc.getState(), c.getAddress()).withValue(rc.getValue());
	}

	public boolean evalA(String source) {
		return evalA(CONTEXT, source);
	}

	public boolean evalA(Context<?> ctx, String source) {
		return eval(ctx, source) instanceof Address;

	}

	public boolean evalB(String source) {
		return ((CVMBool)eval(source)).booleanValue();
	}

	public boolean evalB(Context<?> ctx, String source) {
		return ((CVMBool)eval(ctx, source)).booleanValue();
	}

	public double evalD(Context<?> ctx, String source) {
		ACell result=eval(ctx,source);
		CVMDouble d=RT.castDouble(result);
		if (d==null) throw new ClassCastException("Expected Double, but got: "+RT.getType(result));
		return d.doubleValue();
	}

	public double evalD(String source) {
		return evalD(CONTEXT,source);
	}

	public long evalL(Context<?> ctx, String source) {
		ACell result=eval(ctx,source);
		CVMLong d=RT.castLong(result);
		if (d==null) throw new ClassCastException("Expected Long, but got: "+RT.getType(result));
		return d.longValue();
	}

	public long evalL(String source) {
		return evalL(CONTEXT,source);
	}

	public String evalS(String source) {
		return eval(source).toString();
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T eval(String source) {
		return (T) step(source).getResult();
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T eval(ACell form) {
		return (T) step(CONTEXT,form).getResult();
	}
	
	public <T extends ACell> T eval(Context<?> c, String source) {
		Context<T> rc = step(c,source);
		return rc.getResult();
	}

	public <T extends ACell> Context<T> step(String source) {
		return step(CONTEXT, source);
	}

	@SuppressWarnings("unchecked")
	public <T extends AOp<?>> T comp(ACell form, Context<?> context) {
		context=context.fork(); // fork to avoid corrupting original context
		AOp<?> code = context.expandCompile(form).getResult();
		return (T) code;
	}

	public <T extends AOp<?>> T comp(String source, Context<?> context) {
		return comp(Reader.read(source),context);
	}

	/**
	 * Compiles source code to a CVM Op
	 * @param <T>
	 * @param source
	 * @return CVM Op
	 */
	public <T extends AOp<?>> T comp(String source) {
		return comp(Reader.read(source),CONTEXT);
	}

	/**
	 * Compiles source code to a CVM Op
	 * @param <T>
	 * @param code Source code to compile as a form
	 * @return CVM Op
	 */
	public <T extends AOp<?>> T comp(ACell code) {
		return comp(code,CONTEXT);
	}

	public ACell expand(ACell form) {
		Context<?> ctx=CONTEXT.fork();
		ACell expanded =ctx.expand(form).getResult();
		return expanded;
	}

	public ACell expand(String source) {
		try {
			ACell form=Reader.read(source);
			return expand(form);
		}
		catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}
}
