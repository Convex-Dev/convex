package convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.ResultContext;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.init.Init;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Lookup;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Call;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;


/**
 * Benchmark for applying transactions to CVM state. This is measuring the end-to-end time for processing
 * transactions themselves on the CVM.
 *
 * Deliberately skips stuff around block overhead, signatures etc.
 * This is useful since we expect signatures etc. to be checked in separate threads and
 * therefore we get a measure of raw CVM performance separate from networking / validation
 * tasks.
 */
public class CVMBenchmark {
	static State STATE;
	static Address HERO;
	static Address MARKET;

	static { 
		STATE=Benchmarks.STATE;
		HERO=Benchmarks.HERO;
		
		// Move some USD to Hero
		Context ctx=Context.create(STATE, Init.INIT_ADDRESS);
		ctx=ctx.eval(Reader.read("(do (import convex.fungible :as fun) (import currency.CFUSD :as usd) (fun/transfer usd "+HERO+" 0))"));
		if (ctx.isError()) throw new Error("Problem moving USD: "+ctx.getError().toString());
		STATE=ctx.getState();

		// Get a USD MARKET
		ctx=Context.create(STATE, HERO);
		ctx=ctx.eval(Reader.read("(do (import currency.CFUSD :as usd) "
				+ "(import torus.exchange :as torus) "
				+ "(def market (torus/create-market usd)))"));
		if (ctx.isError()) throw new Error("Problem getting market: "+ctx.getError().toString());
		MARKET=(Address) ctx.getResult();
		
		STATE=ctx.getState();
	}
	
	@Benchmark
	public void smallTransfer() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Transfer.create(addr,1, Benchmarks.VILLAIN, 1000);
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getResult();
	}

	@Benchmark
	public void simpleCalculationStatic() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, convex.core.lang.ops.Invoke.create(Constant.create(Core.PLUS),Constant.of(1L),Constant.of(2L)));
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getResult();
	}

	@Benchmark
	public void simpleCalculationDynamic() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, convex.core.lang.ops.Invoke.create(Lookup.create("+"),Constant.of(1L),Constant.of(2L)));
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getResult();
	}
	
	@Benchmark
	public void minimalTransaction() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, Constant.of(null));
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getValue();
	}
	
	@Benchmark
	public void nullInvoke() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, (ACell)null);
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getValue();
	}

	@Benchmark
	public void defInEnvironment() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, convex.core.lang.ops.Def.create("a", Constant.of(13L)));
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getResult();
	}
	
	@Benchmark
	public void queryAccountHoldings() {
		State s=STATE;
		Address addr=HERO;
		@SuppressWarnings("unused")
		ACell result=s.getAccount(addr).getHoldings();
	}

	
	@Benchmark
	public void deployToken() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, Reader.read("(do (import convex.fungible :as fun) (deploy (fun/build-token {:supply 1000000})))"));
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getResult();
	}
	
	static final ATransaction buyTrade=Invoke.create(HERO,1, Reader.read("(torus/buy-tokens usd 10)"));
	
	@Benchmark
	public ACell dexMarketTrade() {
		State s=STATE;
		ATransaction trans=buyTrade;
		ResultContext ctx=s.applyTransaction(trans);
		return ctx.context.getResult();
	}

	@Benchmark
	public void contractCall() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Call.create(addr,1L, Init.REGISTRY_ADDRESS, Symbols.REGISTER, Vectors.of(Maps.of(Keywords.NAME,Strings.create("Bob"))));
		ResultContext ctx=s.applyTransaction(trans);
		ctx.context.getResult();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(CVMBenchmark.class);
		new Runner(opt).run();
	}
}
