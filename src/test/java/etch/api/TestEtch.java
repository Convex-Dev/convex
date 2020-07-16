package etch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;

public class TestEtch {
	private static final int ITERATIONS = 3;

	@Test
	public void testTempStore() throws IOException {
		Etch etch = Etch.createTempEtch();

		AVector<Integer> v=Vectors.of(1,2,3);
		Hash h = v.getHash();
		Ref<ACell> r=Ref.create(v);

		assertNull(etch.read(h));
		
		// write the Ref
		Ref<ACell> r2=etch.write(h, r);
		
		assertEquals(v.getEncoding(), etch.read(h).getValue().getEncoding());
		
		assertEquals(h,r2.getHash());
	}

	@Test
	public void testRandomWritesStore() throws IOException, BadFormatException {
		Etch etch = Etch.createTempEtch();
		int COUNT = 1000;
		for (int i = 0; i < COUNT; i++) {
			Long a = (long) i;
			AVector<Long> v=Vectors.of(a);
			Hash key = v.getHash();

			etch.write(key, Ref.create(v));

			Ref<ACell> r2 = etch.read(key);
			assertEquals(v,r2.getValue());
			assertNotNull(r2, "Stored value not found for vector value: " + v);
		}

		for (int ii = 0; ii < ITERATIONS; ii++) {
			for (int i = 0; i < COUNT; i++) {
				Long a = (long) i;
				AVector<Long> v=Vectors.of(a);
				Hash key = v.getHash();
				Ref<ACell> r2 = etch.read(key);

				assertNotNull(r2, "Stored value not found for vector value: " + v);
				assertEquals(v, r2.getValue());
			}
		}
	}
}
