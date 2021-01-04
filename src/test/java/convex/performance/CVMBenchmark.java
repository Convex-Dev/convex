package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.Init;
import convex.core.State;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Lookup;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Call;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;

/**
 * Benchmark for applying transactions to CVM state. Skips signatures etc.
 */
public class CVMBenchmark {
	
	@Benchmark
	public void smallTransfer() {
		State s=Init.STATE;
		Address addr=Init.HERO;
		ATransaction trans=Transfer.create(1, Init.VILLAIN, 1000);
		Context<Double>  ctx=s.applyTransaction(addr, trans);
		ctx.getValue();
	}
	 
	@Benchmark
	public void simpleCalculationStatic() {
		State s=Init.STATE;
		Address addr=Init.HERO;
		ATransaction trans=Invoke.create(1, convex.core.lang.ops.Invoke.create(Constant.create(Core.PLUS),Constant.create(1L),Constant.create(2L)));
		Context<Double>  ctx=s.applyTransaction(addr, trans);
		ctx.getValue();
	}
	
	@Benchmark
	public void simpleCalculationDynamic() {
		State s=Init.STATE;
		Address addr=Init.HERO;
		ATransaction trans=Invoke.create(1, convex.core.lang.ops.Invoke.create(Lookup.create("+"),Constant.create(1L),Constant.create(2L)));
		Context<Double>  ctx=s.applyTransaction(addr, trans);
		ctx.getValue();
	}
	
	@Benchmark
	public void defInEnvironment() {
		State s=Init.STATE;
		Address addr=Init.HERO;
		ATransaction trans=Invoke.create(1, convex.core.lang.ops.Def.create("a", Constant.create(13L)));
		Context<Double>  ctx=s.applyTransaction(addr, trans);
		ctx.getValue();
	}
	
	@Benchmark
	public void contractCall() {
		State s=Init.STATE;
		Address addr=Init.HERO;
		ATransaction trans=Call.create(1L, Init.REGISTRY_ADDRESS, Symbols.REGISTER, Vectors.of(Maps.of(Keywords.NAME,Strings.create("Bob"))));
		Context<Double>  ctx=s.applyTransaction(addr, trans);
		ctx.getValue();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(CVMBenchmark.class);
		new Runner(opt).run();
	}
}
