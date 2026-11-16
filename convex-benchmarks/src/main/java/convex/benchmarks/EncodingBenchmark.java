package  convex.benchmarks;

import java.io.IOException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.store.MemoryStore;
import convex.core.store.NullStore;

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

	/** A block's worth of signed transactions with every cell included, as a peer receives them */
	static Blob multiCell;

	static {
		AVector<?> v=Vectors.of(1,true,Symbols.FOO,Sets.of(1,2,3),Maps.empty());
		enc=v.getEncoding();

		AKeyPair kp=AKeyPair.createSeeded(42);
		AVector<ACell> txs=Vectors.empty();
		for (int i=0; i<100; i++) {
			Invoke tx=Invoke.create(Address.create(12), i+1, Vectors.of(Symbols.TRANSFER, Address.create(13), 1000+i));
			txs=txs.conj(kp.signData(tx));
		}
		multiCell=Format.encodeMultiCell(txs,true);
	}

	@Benchmark
	public void encodingViaBlob() throws BadFormatException {
		AVector<?> v2=NullStore.INSTANCE.decode(enc);
		v2.getEncoding();
	}

	@Benchmark
	public ACell decodeMultiCell() throws BadFormatException {
		// A fresh store each time, so no child is already cached
		return new MemoryStore().decodeMultiCell(multiCell);
	}

	@Benchmark
	public ACell decodeMultiCellAndPersist() throws BadFormatException, IOException {
		MemoryStore store=new MemoryStore();
		return Cells.persist(store.decodeMultiCell(multiCell),store);
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EncodingBenchmark.class);
		new Runner(opt).run();
	}
}
