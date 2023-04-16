package  convex.benchmarks;

import java.nio.ByteBuffer;

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
import convex.core.lang.Symbols;

/**
 * Benchmarks for data encoding
 * 
 */
public class EncodingBenchmark {
	
	static Blob enc;
	static ByteBuffer buf;
	
	static {
		AVector<?> v=Vectors.of(1,true,Symbols.FOO,Sets.of(1,2,3),Maps.empty());
		enc=v.getEncoding();
		
		buf=ByteBuffer.allocate(1000);
		buf=v.write(buf);
		buf.flip();
		
	}
	
	@Benchmark
	public void encodingViaBlob() throws BadFormatException {
		AVector<?> v2=Format.read(enc);
		v2.getEncoding();
	}

	@Benchmark
	public void encodingViaBuffer() throws BadFormatException {
		buf.position(0);
		AVector<?> v2=Format.read(buf);
		v2.getEncoding();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EncodingBenchmark.class);
		new Runner(opt).run();
	}
}
