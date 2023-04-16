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
	
	@SuppressWarnings("unused")
	@Benchmark
	public void encodingViaBlob() throws BadFormatException {
		AVector<?> v=Vectors.of(1,true,Symbols.FOO,Sets.of(1,2,3),Maps.empty());
		v.attachEncoding(null);
		
		Blob enc=Format.encodedBlob(v);
		AVector<?> v2=Format.read(enc);
		Blob enc2=v2.getEncoding();
	}

	@SuppressWarnings("unused")
	@Benchmark
	public void encodingViaBuffer() throws BadFormatException {
		AVector<?> v=Vectors.of(1,true,Symbols.FOO,Sets.of(1,2,3),Maps.empty());
		v.attachEncoding(null);
		
		ByteBuffer buf=ByteBuffer.allocate(1000);
		Format.write(buf, v);
		buf.flip();
		AVector<?> v2=Format.read(buf);
		Blob enc2=v2.getEncoding();
	}
	

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EncodingBenchmark.class);
		new Runner(opt).run();
	}
}
