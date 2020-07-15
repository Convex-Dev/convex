package convex.store;

import static org.junit.Assert.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.function.Consumer;

import org.junit.Test;

import convex.core.crypto.Hash;
import convex.core.data.AMap;
import convex.core.data.Blob;
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

			AMap<String, String> data = Maps.of("foo", "bar3621863168");
			Ref<AMap<String, String>> goodRef = Ref.create(data);
			Hash goodHash = goodRef.getHash();
			assertNull(ms.refForHash(goodHash));

			goodRef.persist();

			Ref<AMap<String, String>> recRef = ms.refForHash(goodHash);
			assertNotNull(recRef);

			assertEquals(data, recRef.getValue());
		} finally {
			Stores.setCurrent(oldStore);
		}
	}

	@Test
	public void testPersistedStatus() throws BadFormatException {
		SecureRandom sr = new SecureRandom();

		// generate Hash of unique secure random bytes to test - should not already be
		// in store
		byte[] bytes = new byte[79];
		sr.nextBytes(bytes);
		Blob value = Blob.wrap(bytes);
		Hash hash = value.getHash();
		assertNotEquals(hash, value);

		Ref<Blob> initialRef = Ref.create(value);
		assertEquals(Ref.UNKNOWN, initialRef.getStatus());
		assertNull(Stores.current().refForHash(hash));
		Ref<Blob> ref = initialRef.persist();
		assertEquals(Ref.PERSISTED, ref.getStatus());
		assertTrue(ref.isPersisted());

		Ref<Blob> newRef = Stores.current().refForHash(hash);
		assertEquals(initialRef, newRef);
		assertEquals(value, newRef.getValue());
	}

	@Test
	public void testNoveltyHandler() {
		AStore oldStore = Stores.current();
		MemoryStore ms = new MemoryStore();
		ArrayList<Ref<Object>> al = new ArrayList<>();
		try {
			Stores.setCurrent(ms);
			Object data = Samples.INT_VECTOR_10;

			Consumer<Ref<Object>> handler = r -> al.add(r);

			Ref<Object> dataRef = Ref.create(data);
			Hash dataHash = dataRef.getHash();
			assertNull(ms.refForHash(dataHash));

			dataRef.persist(handler);
			assertEquals(1, al.size());
			assertEquals(data, al.get(0).getValue());

			Ref.create(Samples.INT_VECTOR_300).persist();
			assertEquals(1, al.size()); // no new novelty transmitted
		} finally {
			Stores.setCurrent(oldStore);
		}
	}
}
