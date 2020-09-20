package convex.performance;

import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import etch.EtchStore;

public class EtchBenchmark {
	
	static final EtchStore store=EtchStore.createTemp();
	
	static long ix=0;
	
	@SuppressWarnings("unchecked")
	static Ref<ACell>[] refs=new Ref[1000];
	
	static final Random rand=new Random();
	
	static {
		for (int i=0; i<1000; i++) {
			AVector<Long> v=Vectors.of(0L,(long)i);
			Ref<ACell> r=v.getRef();
			refs[i]=r;
			r.getHash();
			store.persistRef(r, null);
		}
	}
	
	@Benchmark
	public void writeData() {
		AVector<Long> v=Vectors.of(1L,ix++);
		store.persistRef(v.getRef(), null);
	}
	
	@Benchmark
	public void readDataRandom() {
		int ix=rand.nextInt(1000);
		store.refForHash(refs[ix].getHash());
	}
	
	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EtchBenchmark.class);
		new Runner(opt).run();
	}
}
