package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Symbols;
import convex.core.data.prim.CVMDouble;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.test.Samples;

/**
 * Fuzz testing for data formats.
 * 
 * "Testing leads to failure, and failure leads to understanding." - Burt Rutan
 */
public class FuzzTestFormat {

	private static final int NUM_FUZZ = 5000;
	private static Random r = new Random(3244);

	@Test
	public void fuzzTest() {
		// create lots of random blobs, see what is readable

		for (int i = 0; i < NUM_FUZZ; i++) {
			long stime = System.currentTimeMillis();
			r.setSeed(i * 1007);
			Blob b = Blob.createRandom(r, 200);
			try {
				doFuzzTest(b);
				doMutationTest(b);
			} catch (BadFormatException e) {
				/* OK */
			} catch (MissingDataException e) {
				/* also OK */
			}

			// This happens sometimes, e.g. if loading Core Def 
			if (System.currentTimeMillis() > stime + 100) {
				System.err.println("Slow fuzz test: " + b);
			}
		}
	}
	
	@Test 
	public void fuzzExamples() throws BadFormatException  {
		doCellFuzzTests(Symbols.FOO);
		doCellFuzzTests(Samples.INT_VECTOR_10);
		doCellFuzzTests(CVMDouble.POSITIVE_INFINITY);
		
	}
	
	@Test
	public void regressionExamples() {
		// Interpreted as BlobTree will nil children (count 0xff00 = 32640)
		assertThrows(BadFormatException.class,()->doFuzzTest(Blob.fromHex("31ff0000000000000034"))); 
		
		// Interpreted as VectorTree with 768 elements
		assertThrows(BadFormatException.class,()->doFuzzTest(Blob.fromHex("80860000000000000034"))); 
	}
	
	public static void doCellFuzzTests(ACell c)  {
		for (int i = 0; i < 1000; i++) {
			Blob b=Cells.encode(c);
			try {
				doMutationTest(b);
			} catch (Exception e) {
				throw Utils.sneakyThrow(e);
			}
		}
	}

	private static void doFuzzTest(Blob b) throws BadFormatException {
		ACell v;
		try {
			v = Format.read(b,0);
		} catch (ArrayIndexOutOfBoundsException e) {
			// We read past buffer, so basically OK up to that point
			// Try again with bigger buffer!
			if (b.count()>0) doFuzzTest(b.append(b).toFlatBlob());
			return;
		}
		
		// If we have read the object, check that we can validate as a cell, at minimum
		try {
			RT.validate(v);
		} catch (InvalidDataException e) {
			throw new BadFormatException("Validation failed",e);
		} catch (MissingDataException e) {
			throw new BadFormatException("Validation due to missing data",e);
		}

		// if we manage to read the object and it is not a Ref, it must be in canonical
		// format!
		assertTrue(Cells.isCanonical(v),()->"Not canonical: "+Utils.getClassName(v));
		
		Blob b2 = Cells.encode(v);
		assertEquals(v, Format.read(b2),
				() -> "Expected to be able to regenerate value: " + v + " of type " + Utils.getClass(v));

		// recursive fuzzing on this value
		// this is good to test small mutations of
		if (r.nextDouble() < 0.8) {
			doMutationTest(b2);
		}
	}

	public static void doMutationTest(Blob b) {
		byte[] bs = b.getBytes();
		bs[r.nextInt(bs.length)] += (byte) r.nextInt(255);
		Blob fuzzed=Blob.wrap(bs);
		try {
			doFuzzTest(fuzzed);
		} catch (BadFormatException e) {
			/* OK */
		} catch (Exception e) {
			System.err.println("Fuzz test bad blob: "+fuzzed);
			throw e;
		}
	}
}
