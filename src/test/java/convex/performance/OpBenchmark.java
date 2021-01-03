package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Invoke;

public class OpBenchmark {
	
	static final Context<?> CTX=TestState.INITIAL_CONTEXT.fork();
	
	static final AOp<Object> loopOp=CTX.expandCompile(Reader.read("(dotimes [i 1000])")).getResult();
	@Benchmark
	public void emptyLoop() {
		CTX.execute(loopOp);
	}
	
	static final AOp<Object> constantOp=CTX.expandCompile(Reader.read("1")).getResult();
	@Benchmark
	public void constant() {
		CTX.execute(constantOp);
	}
	
	// sum with dynamic core lookup
	static final AOp<Object> simpleSum=CTX.expandCompile(Reader.read("(+ 1 2)")).getResult();
	@Benchmark
	public void simpleSum() {
		CTX.execute(simpleSum);
	}
	 
	// sum without dynamic core lookup (much faster!!)
	static final AOp<Object> simpleSum2=Invoke.create(Constant.create(Core.PLUS),Constant.create(1L),Constant.create(2));
	@Benchmark
	public void simpleSum2() {
		CTX.execute(simpleSum2);
	}
	 


	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(OpBenchmark.class);
		new Runner(opt).run();
	}
}
