package convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

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
 * Skips stuff around transactions, block overhead, signatures etc.
 */
public class CVMBenchmark {
	static State STATE=Benchmarks.STATE;
	static Address HERO=Benchmarks.HERO;

	@Benchmark
	public void smallTransfer() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Transfer.create(addr,1, Benchmarks.VILLAIN, 1000);
		Context<ACell>  ctx=s.applyTransaction(trans);
		ctx.getValue();
	}

	@Benchmark
	public void simpleCalculationStatic() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, convex.core.lang.ops.Invoke.create(Constant.create(Core.PLUS),Constant.of(1L),Constant.of(2L)));
		Context<ACell>  ctx=s.applyTransaction(trans);
		ctx.getValue();
	}

	@Benchmark
	public void simpleCalculationDynamic() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, convex.core.lang.ops.Invoke.create(Lookup.create("+"),Constant.of(1L),Constant.of(2L)));
		Context<ACell> ctx=s.applyTransaction(trans);
		ctx.getValue();
	}

	@Benchmark
	public void defInEnvironment() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Invoke.create(addr,1, convex.core.lang.ops.Def.create("a", Constant.of(13L)));
		Context<ACell>  ctx=s.applyTransaction(trans);
		ctx.getValue();
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
		Context<ACell>  ctx=s.applyTransaction(trans);
		ctx.getValue();
	}

	@Benchmark
	public void contractCall() {
		State s=STATE;
		Address addr=HERO;
		ATransaction trans=Call.create(addr,1L, Init.REGISTRY_ADDRESS, Symbols.REGISTER, Vectors.of(Maps.of(Keywords.NAME,Strings.create("Bob"))));
		Context<ACell>  ctx=s.applyTransaction(trans);
		ctx.getValue();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(CVMBenchmark.class);
		new Runner(opt).run();
	}
}
