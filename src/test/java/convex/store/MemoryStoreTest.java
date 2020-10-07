package convex.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.Test;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.Stores;
import convex.test.Samples;

public class MemoryStoreTest {

	private static final Hash BAD_HASH = Samples.BAD_HASH;

	@Test
	public void testEmptyStore() {
		AStore oldStore = Stores.current();
		MemoryStore ms = new MemoryStore();
		try {
			Stores.setCurrent(ms);
			assertTrue(oldStore != ms);
			assertEquals(ms, Stores.current());

			assertNull(ms.refForHash(BAD_HASH));

			AMap<String, String> data = Maps.of(Keywords.CODE,Address.ZERO);
			Ref<AMap<String, String>> goodRef = data.getRef();
			Hash goodHash = goodRef.getHash();
			assertNull(ms.refForHash(goodHash));

			goodRef.persist();

			if (!(data.isEmbedded())) {
				Ref<AMap<String, String>> recRef = ms.refForHash(goodHash);
				assertNotNull(recRef);
				assertEquals(data, recRef.getValue());
			}

		} finally {
			Stores.setCurrent(oldStore);
		}
	}

	@Test
	public void testPersistedStatus() throws BadFormatException {
		// generate Hash of unique secure random bytes to test - should not already be
		// in store
		Blob value = Blob.createRandom(new Random(), Format.MAX_EMBEDDED_LENGTH);
		Hash hash = value.getHash();
		assertNotEquals(hash, value);

		Ref<Blob> initialRef = value.getRef();
		assertEquals(Ref.UNKNOWN, initialRef.getStatus());
		assertNull(Stores.current().refForHash(hash));
		Ref<Blob> ref = initialRef.persist();
		assertEquals(Ref.PERSISTED, ref.getStatus());
		assertTrue(ref.isPersisted());

		if (!(value.isEmbedded())) {
			Ref<Blob> newRef = Stores.current().refForHash(hash);
			assertEquals(initialRef, newRef);
			assertEquals(value, newRef.getValue());
		}
	}

	@Test
	public void testNoveltyHandler() {
		AStore oldStore = Stores.current();
		MemoryStore ms = new MemoryStore();
		ArrayList<Ref<ACell>> al = new ArrayList<>();
		try {
			Stores.setCurrent(ms);
			AVector<Integer> data = Samples.INT_VECTOR_300;

			Consumer<Ref<ACell>> handler = r -> al.add(r);

			Ref<AVector<Integer>> dataRef = data.getRef();
			Hash dataHash = dataRef.getHash();
			assertNull(ms.refForHash(dataHash));

			dataRef.persist(handler);
			int num=al.size();
			assertTrue(num>0);
			assertEquals(data, al.get(num-1).getValue());

			Samples.INT_VECTOR_300.getRef().persist();
			assertEquals(num, al.size()); // no new novelty transmitted
		} finally {
			Stores.setCurrent(oldStore);
		}
	}
}
