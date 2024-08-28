package convex.benchmarks;

import java.io.IOException;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;

/**
 * Benchmarks from the Etch database
 * 
 * We are interested in random writes since the use of hashes as database
 * keys results in a fairly random distribution of write keys.
 * 
 * Use of relatively small data size is reasonable since we expect majority of 
 * reads to be in cache (either recently written, or frequently access data such 
 * as commonly used libraries or actors).
 */
public class EtchBenchmark {
	
	static final EtchStore store;
	
	static long nonce=0;
	
	static int NUMVALS=10000;
	
	@SuppressWarnings("unchecked")
	static Ref<ACell>[] refs=new Ref[NUMVALS];
	
	static final Random rand=new Random();
	
	static {
		try {
			store =EtchStore.createTemp();
			for (int i=0; i<NUMVALS; i++) {
				AVector<CVMLong> v=Vectors.of(0L,(long)i);
				Ref<ACell> r=v.getRef();
				refs[i]=r;
				r.getHash();
				store.storeTopRef(r, Ref.STORED, null);
			}	
		} catch (IOException e) {
			throw new Error(e);
		}

		System.out.println("Refs stored for testing");
	}
	
	@Benchmark
	public void writeData() throws IOException {
		AVector<CVMLong> v=Vectors.of(1L,nonce++);
		store.storeTopRef(v.getRef(), Ref.STORED, null);
	}
	
	@Benchmark
	public void readDataRandom() throws IOException {
		int ix=rand.nextInt(NUMVALS);
		Ref<?> rix=refs[ix];
		Hash h=rix.getHash();
		// store.refForHash(h);
		//try {
			Ref<ACell> r = store.readStoreRef(h);
			if (r==null) {
			//	System.out.println(h+" "+ix+refs[ix].getValue());
			//	r = store.readStoreRef(h);
			} else {
				r.getValue();
			}
		//} catch (Throwable t) {
		//	System.out.println(h+" "+ix+refs[ix].getValue());
		//	throw t;
		//}
	}
	
	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EtchBenchmark.class);
		new Runner(opt).run();
	}
}
