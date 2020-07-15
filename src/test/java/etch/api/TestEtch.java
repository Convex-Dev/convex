package etch.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.Hash;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keywords;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class TestEtch {
	private static final int ITERATIONS = 3;

	@Test
	public void testTempStore() throws IOException {
		Etch etch = Etch.createTempEtch();

		Blob b = Format.encodedBlob(Keywords.STORE);
		Hash h = b.getHash();

		assertNull(etch.read(h));
		etch.write(h, b);
		assertEquals(b, etch.read(h));
	}

	/**
	 * Test creating synthetic keys that all collide down to the lowest level
	 * 
	 * @throws IOException
	 */
	@Test
	public void testAdjacentWritesStore() throws IOException {
		Etch etch = Etch.createTempEtch();

		byte[] bs = new byte[32];
		for (int i = 0; i < 1000; i++) {
			Utils.writeInt(i, bs, 28);
			Hash key = Hash.wrap(bs);

			etch.write(key, key.toBlob());
		}

		for (int ii = 0; ii < ITERATIONS; ii++) {
			for (int i = 0; i < 1000; i++) {
				Utils.writeInt(i, bs, 28);
				Hash key = Hash.wrap(bs);
				if (i == 105) {
					Utils.writeInt(i, bs, 28);
				}
				Blob b = etch.read(key); // should be blob of length 32
				assertNotNull(b);
				b.getBytes(bs, 0);
				assertEquals(i, Utils.readInt(bs, 28));
			}
		}
	}

	@Test
	public void testRandomWritesStore() throws IOException, BadFormatException {
		Etch etch = Etch.createTempEtch();
		int COUNT = 1000;
		for (int i = 0; i < COUNT; i++) {
			Long a = (long) i;
			Hash key = Hash.compute(a);

			etch.write(key, Format.encodedBlob(a));

			Blob b = etch.read(key);
			assertNotNull(b, "Blob not found for value: " + i);
		}

		for (int ii = 0; ii < ITERATIONS; ii++) {
			for (int i = 0; i < COUNT; i++) {
				Long a = (long) i;
				Hash key = Hash.compute(a);
				Blob b = etch.read(key);

				assertNotNull(b, "Blob not found for value: " + i);
				assertEquals(a, Format.read(b));
			}
		}
	}
}
