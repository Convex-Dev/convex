package  convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.crypto.Hashing;
import convex.core.data.AArrayBlob;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.prim.CVMLong;

/**
 * Benchmarks for hashing performance.
 * 
 * Included to test hashing algorithm performance, since this might be a
 * bottleneck in some scenarios.
 */
public class HashBenchmark {

	@Benchmark
	public void longHash_SHA_256() {
		CVMLong l = CVMLong.create(17L);
		AArrayBlob d = Cells.encode(l);
		Hashing.sha256(d.getInternalArray());
	}
	
	static final byte[] b1=new byte[1];
	@Benchmark
	public void sha3256Hash1() {
		Blob b=Blob.wrap(b1);
		b.getContentHash();
	}

	static final byte[] b1000=new byte[1000];
	@Benchmark
	public void sha3256Hash1000() {
		Blob b=Blob.wrap(b1000);
		b.getContentHash();
	}

	@Benchmark
	public void kilobyteHash() {
		Blob b = Blob.wrap(new byte[1024]);
		b.getHash();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(HashBenchmark.class);
		new Runner(opt).run();
	}
}
