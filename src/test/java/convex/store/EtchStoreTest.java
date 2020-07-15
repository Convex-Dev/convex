package convex.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import convex.core.store.Stores;
import convex.test.Samples;
import etch.store.EtchStore;

public class EtchStoreTest {

	private static final Hash BAD_HASH = Samples.BAD_HASH;
	private EtchStore es = EtchStore.createTemp();

	@Test
	public void testEmptyStore() {
		AStore oldStore = Stores.current();
		try {
			Stores.setCurrent(es);
			assertTrue(oldStore != es);
			assertEquals(es, Stores.current());

			assertNull(es.refForHash(BAD_HASH));

			AMap<String, String> data = Maps.of("foo", "bar3621863168");
			Ref<AMap<String, String>> goodRef = Ref.create(data);
			Hash goodHash = goodRef.getHash();
			assertNull(es.refForHash(goodHash));

			goodRef.persist();

			Ref<AMap<String, String>> recRef = es.refForHash(goodHash);
			assertNotNull(recRef);

			assertEquals(data, recRef.getValue());
		} finally {
			Stores.setCurrent(oldStore);
		}
	}

	@Test
	public void testPersistedStatus() throws BadFormatException {
		AStore oldStore = Stores.current();
		try {
			Stores.setCurrent(es);
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
		} finally {
			Stores.setCurrent(oldStore);
		}
	}

	@Test
	public void testNoveltyHandler() {
		AStore oldStore = Stores.current();
		ArrayList<Ref<Object>> al = new ArrayList<>();
		try {
			Stores.setCurrent(es);
			Object data = Samples.INT_VECTOR_10;

			// handler that records added refs
			Consumer<Ref<Object>> handler = r -> al.add(r);

			Ref<Object> dataRef = Ref.create(data);
			Hash dataHash = dataRef.getHash();
			assertNull(es.refForHash(dataHash));

			dataRef.persist(handler);
			assertEquals(1, al.size()); // got new novelty
			assertEquals(data, al.get(0).getValue());

			Ref.create(Samples.INT_VECTOR_300).persist();
			assertEquals(1, al.size()); // no new novelty transmitted
		} finally {
			Stores.setCurrent(oldStore);
		}
	}
}
