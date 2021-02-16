package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.crypto.Hash;
import convex.core.data.AArrayBlob;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.prim.CVMLong;

public class HashBenchmark {

	@Benchmark
	public void longHash_KECCAK_256() {
		CVMLong l = CVMLong.create(17L);
		AArrayBlob d = Format.encodedBlob(l);
		d.getContentHash();
	}

	@Benchmark
	public void longHash_SHA_256() {
		CVMLong l = CVMLong.create(17L);
		AArrayBlob d = Format.encodedBlob(l);
		Hash.sha256(d.getInternalArray());
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
