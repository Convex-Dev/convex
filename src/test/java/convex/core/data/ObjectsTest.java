package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Generic test functions for arbitrary Data Objects.
 */
public class ObjectsTest {

	/**
	 * Generic tests for an Cell
	 * 
	 * @param a
	 * @throws InvalidDataException
	 */
	public static void doCellTests(ACell a) {
		String edn = a.ednString();
		assertNotNull(edn);

		try {
			a.validateCell();
			doCellStorageTest(a);
		} catch (InvalidDataException e) {
			throw Utils.sneakyThrow(e);
		}
		
		if (a.getRefCount()>0) {
			doRefContainerTests(a);
		}
		

		doAnyValueTests(a);
	}

	private static void doCellStorageTest(ACell a) throws InvalidDataException {
		AStore temp=Stores.current();
		try {
			// test using a new memory store
			MemoryStore ms=new MemoryStore();
			Stores.setCurrent(ms);
			
			Ref<ACell> r=Ref.create(a);
			
			Hash hash=r.getHash();
			
			assertNull(ms.refForHash(hash));
			
			// persist the Ref
			Ref.createPersisted(a);
			
			// retrieve from store
			Ref<ACell> rr=ms.refForHash(hash);
			
			if (r.isEmbedded()) {
				// nothing should be persisted
				assertNull(rr);
			} else {
				// should be able to retrieve and validate complete structure
				assertNotNull(rr,()->"Failed to retrieve from store with "+Utils.getClassName(a) + " = "+a);
				ACell b=rr.getValue();
				b.validate();
				assertEquals(a,b);
			}
		} finally {
			Stores.setCurrent(temp);
		}
	}

	/**
	 * Generic tests for any data object.
	 * 
	 * @param a
	 */
	public static void doAnyValueTests(Object a) {
		assertTrue(Format.isCanonical(a));

		Ref<Object> r = Ref.create(a).persist();
		assertEquals(a, r.getValue());

		Blob b = Format.encodedBlob(a);

		assertTrue(b.length <= Format.LIMIT_ENCODING_LENGTH);
		try {
			Object a2;
			a2 = Format.read(b);
			assertEquals(a, a2);
		} catch (BadFormatException e) {
			throw new Error("Can't read encoding: " + b.toHexString(), e);
		}
	}

	/**
	 * Tests for any value implementing the IRefContainer interface
	 * 
	 * @param a
	 */
	private static void doRefContainerTests(ACell a) {
		long tcount = Utils.totalRefCount(a);
		int rcount = Utils.refCount(a);

		assertTrue(rcount <= tcount);
	}

}
