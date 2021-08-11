package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
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

	private static final int NUM_FUZZ = 3000;
	private static Random r = new Random(7855875);

	@Test
	public void fuzzTest() {
		// create lots of blobs, see what is readable

		for (int i = 0; i < NUM_FUZZ; i++) {
			long stime = System.currentTimeMillis();
			r.setSeed(i * 1000);
			Blob b = Blob.createRandom(r, 100);
			try {
				doFuzzTest(b);
			} catch (BadFormatException e) {
				/* OK */
			} catch (BufferUnderflowException e) {
				/* also OK */
			} catch (MissingDataException e) {
				/* also OK */
			}

			if (System.currentTimeMillis() > stime + 100) {
				System.err.println("Slow fuzz test: " + b);
			}
		}
	}

	private static void doFuzzTest(Blob b) throws BadFormatException {
		ByteBuffer bb = b.getByteBuffer();

		ACell v = Format.read(bb);

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
		assertEquals(bb.position(), b2.count(), () -> {
			return "Bad length re-reading " + Utils.getClass(v) + ": " + v + " with encoding " + b.toHexString()
					+ " and re-encoding" + b2.toHexString();
		});
		
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
