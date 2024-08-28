package convex.benchmarks;

import java.io.IOException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.AProvider;
import convex.core.crypto.ASignature;
import convex.core.crypto.bc.BCProvider;
import convex.core.crypto.sodium.SodiumProvider;
import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Ref;
import convex.core.data.SignedData;

public class SignatureBenchmark {
	protected static final AProvider SDPROVIDER=new SodiumProvider();
	protected static final AProvider BCPROVIDER=new BCProvider();
	protected static final AProvider PROVIDER=SDPROVIDER;

	private static final AKeyPair KEYPAIR=PROVIDER.generate();
	private static final SignedData<ABlob> SIGNED=makeSigned();

	private static SignedData<ABlob> makeSigned() {
		SignedData<ABlob> signed= KEYPAIR.signData(Blobs.fromHex("cafebabe"));
		try {
			Cells.persist(signed);
		} catch (IOException e) {
			throw new Error(e);
		}
		return signed;
	}
	
	private static final ASignature SIGNATURE=SIGNED.getSignature();

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

		sig.verify(b.getHash(), KEYPAIR.getAccountKey());
	}
	
	@Benchmark
	public void verify() {
		ASignature sig=SIGNATURE;
		PROVIDER.verify(sig,SIGNED.getValue().getHash(), KEYPAIR.getAccountKey());
	}
	
	@SuppressWarnings("unchecked")
	@Benchmark
	public void verifyFromStore() {
		SignedData<ABlob> signed=(SignedData<ABlob>) Ref.forHash(SIGNED.getHash()).getValue();
		signed.checkSignature();
	}


	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(SignatureBenchmark.class);
		new Runner(opt).run();
	}
}
