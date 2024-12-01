package  convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.cvm.Symbols;

/**
 * Benchmarks for data encoding
 * 
 * There doesn't seem to be much in it between blob-based decoding and buffer based decoding.
 * Blob based decoding has the advantage of being able to cache encodings directly however.
 * This is probably a win for persistence, networking etc.?
 * 
 */
public class EncodingBenchmark {
	
	static Blob enc;
	
	static {
		AVector<?> v=Vectors.of(1,true,Symbols.FOO,Sets.of(1,2,3),Maps.empty());
		enc=v.getEncoding();
	}
	
	@Benchmark
	public void encodingViaBlob() throws BadFormatException {
		AVector<?> v2=Format.read(enc);
		v2.getEncoding();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EncodingBenchmark.class);
		new Runner(opt).run();
	}
}
