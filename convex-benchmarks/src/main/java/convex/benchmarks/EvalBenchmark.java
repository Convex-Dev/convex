package convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.ACell;
import convex.core.cvm.Context;
import convex.core.lang.Reader;

public class EvalBenchmark {
	
	static final Context CTX=Benchmarks.context();
	
	private static final ACell eval(ACell form) {
		return CTX.fork().eval(form).getResult();
	}
	
	static final ACell loopOp=Reader.read("(dotimes [i 1000])");
	@Benchmark
	public void emptyLoop() {
		eval(loopOp);
	}
	
	static final ACell constantOp=Reader.read("1");
	@Benchmark
	public void constant() {
		eval(constantOp);
	}
	
	// sum with dynamic core lookup
	static final ACell simpleSum=Reader.read("(+ 1 2)");
	@Benchmark
	public void simpleSum() {
		eval(simpleSum);
	}
	 
	// sum with eval
	static final ACell simpleSum2=Reader.read("(eval '(+ 1 2))");
	@Benchmark
	public void evalSum() {
		eval(simpleSum2);
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EvalBenchmark.class);
		new Runner(opt).run();
	}
}
