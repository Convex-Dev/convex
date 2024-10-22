package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferUnderflowException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

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
		// create lots of blobs, see what is readable

		for (int i = 0; i < NUM_FUZZ; i++) {
			long stime = System.currentTimeMillis();
			r.setSeed(i * 1007);
			Blob b = Blob.createRandom(r, 100);
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
	public void fuzzExamples()  {
		doCellFuzzTests(Symbols.FOO);
	}
	
	public static void doCellFuzzTests(ACell c)  {
		for (int i = 0; i < 1000; i++) {
			Blob b=Format.encodedBlob(c);
			try {
				doFuzzTest(b);
			} catch (Exception e) {
				throw Utils.sneakyThrow(e);
			}
		}
	}

	private static void doFuzzTest(Blob b) throws BadFormatException {
		ACell v;
		try {
			v = Format.read(b);
		} catch (ArrayIndexOutOfBoundsException e) {
			System.out.println("Badd fuzzed read: "+b);
			throw e;
		}
		

		// If we have read the object, check that we can validate as a cell, at minimum
		try {
			RT.validate(v);
		} catch (InvalidDataException e) {
			throw new BadFormatException("Validation failed",e);
		}

		// if we manage to read the object and it is not a Ref, it must be in canonical
		// format!
		assertTrue(Format.isCanonical(v),()->"Not canonical: "+Utils.getClassName(v));
		
		Blob b2 = Format.encodedBlob(v);
		assertEquals(v, Format.read(b2),
				() -> "Expected to be able to regenerate value: " + v + " of type " + Utils.getClass(v));

		// recursive fuzzing on this value
		// this is good to test small mutations of
		if (r.nextDouble() < 0.8) {
			doMutationTest(b2);
		}

	}

	public static void doMutationTest(Blob b) {
		try {
			byte[] bs = b.getBytes();
			bs[r.nextInt(bs.length)] += (byte) r.nextInt(255);
			doFuzzTest(Blob.wrap(bs));
		} catch (BadFormatException e) {
			/* OK */
		} catch (BufferUnderflowException e) {
			/* also OK */
		} catch (MissingDataException e) {
			/* also OK */
		}
	}
}
