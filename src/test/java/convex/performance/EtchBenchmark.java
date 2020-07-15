package convex.performance;

import java.io.IOException;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.crypto.Hash;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Symbol;
import convex.core.exceptions.BadFormatException;
import etch.api.Etch;

public class EtchBenchmark {
	static Etch etch;

	// use pre-generated symbols for testing

	private static final int SYMCOUNT = 1000000;

	static Symbol[] USED_SYMS = new Symbol[SYMCOUNT];
	static Symbol[] UNUSED_SYMS = new Symbol[SYMCOUNT];

	static Random r = new Random(6876876);

	@Benchmark
	public void randomReadInDB() throws BadFormatException, IOException {
		int i = r.nextInt(SYMCOUNT);
		Hash h = USED_SYMS[i].getHash();
		read(h);
	}

	@Benchmark
	public void randomWriteInDB() throws BadFormatException, IOException {
		int i = r.nextInt(SYMCOUNT);
		write(USED_SYMS[i]);
	}

	@Benchmark
	public void randomWriteNewVaues() throws BadFormatException, IOException {
		// note we are incurring the cost of building / hashing the symbol on each
		// iteration here
		int i = r.nextInt();
		write(Symbol.create("" + i));
	}

	@Benchmark
	public void randomReadMissDB() throws BadFormatException, IOException {
		int i = r.nextInt(SYMCOUNT);
		Hash h = UNUSED_SYMS[i].getHash();
		read(h);
	}

	@Benchmark
	public void randomRead50PercentInDB() throws BadFormatException, IOException {
		int i = r.nextInt(SYMCOUNT);
		Symbol s = (r.nextBoolean()) ? USED_SYMS[i] : UNUSED_SYMS[i];
		Hash h = s.getHash();
		read(h);
	}

	private static void write(Symbol symbol) throws IOException {
		etch.write(symbol.getHash(), symbol.getEncoding());
	}

	private static Symbol read(Hash hash) throws IOException, BadFormatException {
		Blob b = etch.read(hash);
		if (b == null) return null;
		return Format.read(b);
	}

	static {
		try {
			etch = Etch.createTempEtch();

			for (int i = 0; i < SYMCOUNT; i++) {
				USED_SYMS[i] = Symbol.create("used" + i);
				UNUSED_SYMS[i] = Symbol.create("unused" + i);
			}

			// touch all the hashes so we have them ready to go
			for (int i = 0; i < SYMCOUNT; i++) {
				USED_SYMS[i].getHash();
				write(USED_SYMS[i]);
				UNUSED_SYMS[i].getHash();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(EtchBenchmark.class);
		new Runner(opt).run();
	}

}
