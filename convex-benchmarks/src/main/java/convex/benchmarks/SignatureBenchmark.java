package convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.SignedData;

public class SignatureBenchmark {

	private static final AKeyPair KEYPAIR=AKeyPair.generate();

	@Benchmark
	public void signData() {
		ABlob b=Blobs.createRandom(16);
		KEYPAIR.signData(b);
	}

	@Benchmark
	public void signVerify() {
		ABlob b=Blobs.createRandom(16);
		SignedData<ABlob> sd=KEYPAIR.signData(b);
		ASignature sig=sd.getSignature();

		sig.verify(sd.getHash(), KEYPAIR.getAccountKey());
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(SignatureBenchmark.class);
		new Runner(opt).run();
	}
}
