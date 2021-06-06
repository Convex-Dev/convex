package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.SignedData;

public class SignatureBenchmark {

	private static final AKeyPair kp=Init.HERO_KP;
	
	@Benchmark
	public void signData() {
		ABlob b=Blobs.createRandom(16);
		kp.signData(b);
	}
	
	@Benchmark
	public void signVerify() {
		ABlob b=Blobs.createRandom(16);
		SignedData<ABlob> sd=kp.signData(b);
		ASignature sig=sd.getSignature();
		
		sig.verify(sd.getHash(), kp.getAccountKey());
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(SignatureBenchmark.class);
		new Runner(opt).run();
	}
}
