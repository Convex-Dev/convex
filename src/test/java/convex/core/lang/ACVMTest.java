package convex.core.lang;

import convex.core.Init;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Syntax;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * Base class for CVM tests that work from a given initial state and context.
 * 
 * Provides utility functions for CVM code execution.
 */
public abstract class ACVMTest {

	protected State INITIAL;
	protected Context<?> CONTEXT;
	protected long INITIAL_JUICE;
	
	/**
	 * Balance of hero's account before spending any juice / funds
	 */
	public final long HERO_BALANCE;

	/**
	 * Balance of hero's account before spending any juice / funds
	 */
	public final long VILLAIN_BALANCE;
	
	protected ACVMTest(State s) {
		this.INITIAL=s;
		CONTEXT=Context.createFake(s,Init.HERO);
		INITIAL_JUICE=CONTEXT.getJuice();
		HERO_BALANCE = INITIAL.getAccount(Init.HERO).getBalance();
		VILLAIN_BALANCE = INITIAL.getAccount(Init.HERO).getBalance();
	}
	

	
	/**
	 * Steps execution in a new forked Context
	 * @param <T>
	 * @param ctx Initial context to fork
	 * @param source
	 * @return New forked context containing step result
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> Context<T> step(Context<?> ctx, String source) {
		// Compile form in forked context
		Context<AOp<ACell>> cctx=ctx.fork();
		ACell form = Reader.read(source);
		cctx = cctx.expandCompile(form);
		if (cctx.isExceptional()) return (Context<T>) cctx;
		AOp<ACell> op = cctx.getResult();

		// Run form in separate forked context to get result context
		Context<T> rctx = ctx.fork();
		rctx=(Context<T>) rctx.run(op);
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
	
	public <T extends ACell> T eval(Context<?> c, String source) {
		c=c.fork();
		try {
			AOp<T> op = compile(c, source);
			Context<T> rc = c.run(op);
			return rc.getResult();
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
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> Context<T> stepAs(Address address, Context<?> c, String source) {
		Context<?> rc = Context.createFake(c.getState(), address);
		rc = step(rc, source);
		return (Context<T>) Context.createFake(rc.getState(), c.getAddress()).withValue(rc.getValue());
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

	public <T extends ACell> Context<T> step(String source) {
		return step(CONTEXT, source);
	}
	
	@SuppressWarnings("unchecked")
	public <T extends AOp<?>> T comp(String source, Context<?> context) {
		ACell form=Reader.read(source);
		AOp<?> code = context.fork().expandCompile(form).getResult();
		return (T) code;
	}
	
	/**
	 * Compiles source code to a CVM Op
	 * @param <T>
	 * @param source
	 * @return CVM Op
	 */
	public <T extends AOp<?>> T comp(String source) {
		return comp(source,CONTEXT);
	}
	
	public Syntax expand(String source) {
		try {
			ACell form=Reader.read(source);
			Syntax expanded =CONTEXT.fork().expand(form).getResult();
			return expanded;
		}
		catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}
}
