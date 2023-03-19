package etch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Refs;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.test.Samples;
import etch.Etch;
import etch.EtchStore;

public class TestEtch {
	private static final int ITERATIONS = 3;
	
	EtchStore store=EtchStore.createTemp();
	EtchStore store2=EtchStore.createTemp();

	@Test
	public void testTempStore() throws IOException {
		Etch etch = store.getEtch();

		AVector<CVMLong> v=Vectors.of(1,2,3);
		Hash h = v.getHash();
		Ref<ACell> r=v.getRef();

		assertNull(etch.read(h));

		// write the Ref
		Ref<ACell> r2=etch.write(h, r);

		assertEquals(v.getEncoding(), etch.read(h).getValue().getEncoding());

		assertEquals(h,r2.getHash());
	}

	@Test
	public void testRandomWritesStore() throws IOException, BadFormatException {
		Etch etch = store.getEtch();

		int COUNT = 1000;
		for (int i = 0; i < COUNT; i++) {
			Long a = (long) i;
			AVector<CVMLong> v=Vectors.of(a);
			Hash key = v.getHash();

			etch.write(key, v.getRef());

			Ref<ACell> r2 = etch.read(key);
			assertEquals(v,r2.getValue());
			assertNotNull(r2, "Stored value not found for vector value: " + v);
		}

		for (int ii = 0; ii < ITERATIONS; ii++) {
			for (int i = 0; i < COUNT; i++) {
				Long a = (long) i;
				AVector<CVMLong> v=Vectors.of(a);
				Hash key = v.getHash();
				Ref<ACell> r2 = etch.read(key);

				assertNotNull(r2, "Stored value not found for vector value: " + v);
				assertEquals(v, r2.getValue());
			}
		}
	}

	@Test
	public void testLargeStore() throws IOException {
		EtchStore store=EtchStore.createTemp();
		Etch etch = store.getEtch();

		// this gets the data saved over 1GB
		// int COUNT = 6120700;
		int COUNT=10000;
		Random random = new Random();
		for (int i = 0; i < COUNT; i++) {
			doStoreWrite(etch, random);
		}
	}

	private void doStoreWrite(Etch etch, Random random) throws IOException {
		AVector<CVMLong> v=Vectors.of(random.nextLong());
		Hash key = v.getHash();
		Ref<ACell> r=v.getRef();

		assertNull(etch.read(key));
		// write the Ref
		Ref<ACell> r2=etch.write(key, r);
		assertEquals(key,r2.getHash());
		assertTrue(etch.getDataLength() > 0);
		// System.out.println(i + " " +  COUNT);
	}
	
	@Test 
	public void testCopyAcrossStores() {
		AString nestedString=Samples.NON_EMBEDDED_STRING;
		ABlob nestedBlob=Samples.NON_EMBEDDED_BLOB;
		ACell v=Vectors.of(1,nestedString,Vectors.of(2,nestedBlob));
		assertTrue(v.isEmbedded());
		assertFalse(v.getRef(1).isEmbedded());
		
		Hash h=v.getHash();
		assertNull(store.refForHash(h));
		
		Ref<ACell> r=store.storeRef(v.getRef(), Ref.PERSISTED, null,true); // note top level
		assertTrue(r.isPersisted());
		assertSame(h,r.getHash()); // TODO: should be identical?
		
		Refs.checkConsistentStores(r, store);
		
		// should now be persisted in first store, but not second
		assertNotNull(store.refForHash(h));
		assertNull(store2.refForHash(h));
		
		Ref<ACell> r2=store2.storeRef(v.getRef(), Ref.PERSISTED, null,true); // note top level
		assertNotNull(store2.refForHash(h));
		
		Refs.checkConsistentStores(r, store); // should be unchanged
		Refs.checkConsistentStores(r2, store2);
		
		ACell v2=r2.getValue();
		assertEquals(v,v2);
		assertNotSame(v,v2);
	}
}
