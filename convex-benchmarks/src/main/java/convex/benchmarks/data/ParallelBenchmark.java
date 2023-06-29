package convex.benchmarks.data;

import java.util.stream.Stream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.benchmarks.Benchmarks;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Benchmark for parallel stream operators on CVM sequences.
 * 
 * We want these to be fast for e.g. index construction or parallel signature verification
 */
public class ParallelBenchmark {
	
	private static final int SIZE = 1000;
	static AVector<CVMLong> source;
	
	static {
		AVector<CVMLong> v=Vectors.empty();
		for (int i=0; i<SIZE; i++) {
			v=v.conj(CVMLong.create(i));
		}
		
		source = v;
	}
	
	private CVMLong sillyTask(CVMLong v) {
		long tot=v.longValue();
		for (int i=0; i<1000; i++) {
			tot=tot+tot;
		}
		return CVMLong.create(tot);
	}
	
	@Benchmark
	public void serial() {
		Stream<CVMLong> stream=source.stream();
		stream.forEach(this::sillyTask);
		//List<CVMLong> a = stream.map(this::sillyTask).collect(Collectors.toList());
		//assert(a.size()==SIZE);
	}

	@Benchmark
	public void parallel() {
		Stream<CVMLong> stream=source.stream().parallel();		
		stream.forEach(this::sillyTask);
		//List<CVMLong> a = stream.map(this::sillyTask).collect(Collectors.toList());
		// assert(a.size()==SIZE);
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(ParallelBenchmark.class);
		new Runner(opt).run();
	}
}
