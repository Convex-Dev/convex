package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.ASet;
import convex.core.data.RefSoft;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.store.MemoryStore;
import convex.test.Samples;

public class MemoryStoreTest {

	private static final Hash BAD_HASH = Samples.BAD_HASH;

	@Test
	public void testEmptyStore() throws IOException {
		MemoryStore ms = new MemoryStore();

		assertNull(ms.refForHash(BAD_HASH));

		AMap<ACell, ACell> data = Maps.of(Keywords.CODE,Address.ZERO);
		Ref<AMap<ACell, ACell>> goodRef = data.getRef();
		Hash goodHash = goodRef.getHash();
		assertNull(ms.refForHash(goodHash));

		goodRef.persist(ms);

		if (!(data.isEmbedded())) {
			Ref<AMap<ACell, ACell>> recRef = ms.refForHash(goodHash);
			assertNotNull(recRef);
			assertEquals(data, recRef.getValue());
		}
	}

	@Test
	public void testPersistedStatus() throws BadFormatException, IOException {
		// generate Hash of unique secure random bytes to test - should not already be
		// in store
		Blob value = Blob.createRandom(new Random(), Format.MAX_EMBEDDED_LENGTH);
		Hash hash = value.getHash();
		assertNotEquals(hash, value);

		Ref<Blob> initialRef = value.getRef();
		assertEquals(Ref.UNKNOWN, initialRef.getStatus());
		assertNull(Samples.TEST_STORE.refForHash(hash));
		Ref<Blob> ref = initialRef.persist(Samples.TEST_STORE);
		assertEquals(Ref.PERSISTED, ref.getStatus());
		assertTrue(ref.isPersisted());

		if (!(value.isEmbedded())) {
			Ref<Blob> newRef = Samples.TEST_STORE.refForHash(hash);
			assertEquals(initialRef, newRef);
			assertEquals(value, newRef.getValue());
		}
	}

	@Test
	public void testNoveltyHandler() throws IOException {
		MemoryStore ms = new MemoryStore();
		ArrayList<Ref<ACell>> al = new ArrayList<>();

		ACell data = Sets.of(Samples.INT_SET_10,15685995L,Samples.INT_VECTOR_300,Samples.MAX_EMBEDDED_BLOB); // should be novel

		Consumer<Ref<ACell>> handler = r -> al.add(r);

		Ref<AVector<CVMLong>> dataRef = data.getRef();
		Hash dataHash = dataRef.getHash();
		assertNull(ms.refForHash(dataHash));

		dataRef.persist(handler, ms);
		int num=al.size();
		assertTrue(num>0);
		assertEquals(data, al.get(num-1).getValue());

		data.getRef().persist(ms);
		assertEquals(num, al.size()); // no new novelty transmitted
	}

	@Test
	public void testNestedPersistedStatus() throws IOException {
		MemoryStore ms = new MemoryStore();

		// Nested structure: vector containing a non-embedded set
		AVector<ACell> nested = Vectors.of(Samples.INT_SET_300, CVMLong.create(42));
		Hash nestedHash = Cells.getHash(nested);

		// Persist and verify both parent and child status in the store
		Cells.persist(nested, ms);

		Ref<?> parentRef = ms.refForHash(nestedHash);
		assertNotNull(parentRef);
		assertTrue(parentRef.getStatus() >= Ref.PERSISTED);

		// Child set should also be individually stored with PERSISTED status
		Hash childHash = Cells.getHash(Samples.INT_SET_300);
		if (!Samples.INT_SET_300.isEmbedded()) {
			Ref<?> childRef = ms.refForHash(childHash);
			assertNotNull(childRef, "Child should be individually retrievable");
			assertTrue(childRef.getStatus() >= Ref.PERSISTED);
		}
	}

	@Test
	public void testPartiallyResolvableTree() throws IOException {
		MemoryStore ms = new MemoryStore();

		// Build a real non-embedded value and get its hash
		Blob realChild = Blob.createRandom(new Random(42), Format.MAX_EMBEDDED_LENGTH);
		Hash realChildHash = realChild.getHash();
		// A hash for data that does NOT exist in this store
		Hash missingHash = Hash.fromHex("dead0000beef0000dead0000beef0000dead0000beef0000dead0000beef0000");

		// Store the real child first
		Cells.persist(realChild, ms);
		assertNotNull(ms.refForHash(realChildHash));

		// Create a RefSoft pointing to missing data in ms
		Ref<ACell> missingRef = RefSoft.createForHash(missingHash, ms);
		assertTrue(missingRef.isMissing());

		// storeTopRef at STORED level should not throw even with a missing ref
		Ref<ACell> storedResult = ms.storeTopRef(missingRef, Ref.STORED, null);
		// Missing ref should be returned unchanged (not crash)
		assertNotNull(storedResult);

		// storeTopRef at PERSISTED level on missing ref should also not throw
		Ref<ACell> persistResult = ms.storeTopRef(missingRef, Ref.PERSISTED, null);
		assertNotNull(persistResult);
		// Status should NOT be PERSISTED since data is missing
		assertTrue(persistResult.getStatus() < Ref.PERSISTED,
			"Missing ref should not claim PERSISTED status");
	}

	@Test
	public void testIncrementalAcquisitionPattern() throws IOException {
		// Simulates the Acquiror pattern: store parent first, then children
		MemoryStore ms = new MemoryStore();

		// Build a structure with non-embedded children
		ASet<ACell> set = (ASet<ACell>) Sets.empty();
		for (int i = 0; i < 200; i++) {
			set = (ASet<ACell>) set.conj(CVMLong.create(i));
		}
		Hash setHash = Cells.getHash(set);

		// Store at STORED level (like Acquiror's Cells.store)
		Cells.store(set, ms);
		Ref<?> storedRef = ms.refForHash(setHash);
		assertNotNull(storedRef, "Parent should be in store after Cells.store");
		assertTrue(storedRef.getStatus() >= Ref.STORED);

		// Now persist fully (like Acquiror's Cells.persist after all data acquired)
		Cells.persist(set, ms);
		Ref<?> persistedRef = ms.refForHash(setHash);
		assertNotNull(persistedRef);
		assertTrue(persistedRef.getStatus() >= Ref.PERSISTED);
	}

	/**
	 * A STORED write that finishes after a PERSISTED write of the same value began
	 * must not lower the status the store records for it. The novelty handler runs
	 * inside the STORED write, after its first child and before its top cell is
	 * recorded, so the PERSISTED write it makes stands in for one racing on
	 * another thread.
	 */
	@Test
	public void testRacingLowerWriteKeepsStatus() throws IOException {
		MemoryStore ms = new MemoryStore();
		Random r = new Random(5791);
		AVector<ACell> value = Vectors.empty();
		for (int i = 0; i < 10; i++) {
			value = value.conj(Blob.createRandom(r, 200));
		}
		assertFalse(value.isEmbedded());
		Hash hash = value.getHash();
		final AVector<ACell> top = value;

		boolean[] raced = {false};
		Consumer<Ref<ACell>> handler = ref -> {
			if (raced[0]) return;
			raced[0] = true;
			Ref<ACell> persisted = ms.storeTopRef(top.getRef(), Ref.PERSISTED, null);
			assertEquals(Ref.PERSISTED, persisted.getStatus());
		};
		Ref<AVector<ACell>> stored = ms.storeTopRef(value.getRef(), Ref.STORED, handler);
		assertTrue(raced[0]);

		assertEquals(Ref.PERSISTED, ms.refForHash(hash).getStatus());
		// The write's result reports what the store records, as Etch.write does
		assertEquals(Ref.PERSISTED, stored.getStatus());
	}

	@Test
	public void testGetRootHashNull() throws IOException {
		try (MemoryStore ms = new MemoryStore()) {
			assertNull(ms.getRootHash());
			assertNull(ms.getRootData());
		}
	}

	@Test
	public void testSetAndGetRootData() throws IOException {
		try (MemoryStore ms = new MemoryStore()) {
			CVMLong data = CVMLong.create(42);
			ms.setRootData(data);

			assertEquals(data, ms.getRootData());
			assertEquals(data.getHash(), ms.getRootHash());
		}
	}
}
