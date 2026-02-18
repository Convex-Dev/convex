package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.cvm.State;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.init.InitTest;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.NullStore;
import convex.core.util.Utils;
import convex.etch.EtchStore;
import convex.test.Samples;

public class StoresTest {
	static EtchStore testStore;
	
	static {
		try {
			testStore=EtchStore.createTemp();
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	@Test public void testInitState() throws InvalidDataException, IOException {
		// Use fresh State
		State s=InitTest.createState();
		Ref<State> sr=Cells.persist(s, testStore).getRef();

		Hash hash=sr.getHash();

		Ref<State> sr2=Ref.forHash(hash, testStore);
		State s2=sr2.getValue();
		s2.validate();
	}
	
	@Test public void testCrossStores() throws InvalidDataException, IOException {
		AStore m1=new MemoryStore();
		AStore m2=new MemoryStore();
		
		AStore e1=testStore;
		AStore e2=EtchStore.createTemp();
		
		// non-emebdded single Cell
		AString nv=Samples.NON_EMBEDDED_STRING; 
		assertFalse(nv.isEmbedded());
		assertTrue(Cells.isCompletelyEncoded(nv));
		
		// small fully embedded cell
		CVMLong ev=CVMLong.ONE;
		assertTrue(ev.isEmbedded());
		assertTrue(Cells.isCompletelyEncoded(ev));
		
		AVector<?> v=Vectors.of(Vectors.of(nv,ev),nv,ev);
		 
		Consumer<ACell> crossTest=x->{
			try {
				doCrossStoreTest(x,e1,e2);
				doCrossStoreTest(x,m1,e1);
				doCrossStoreTest(x,e2,m2);			
				doCrossStoreTest(x,m1,m2);
			} catch (IOException e) {
				throw Utils.sneakyThrow(e);
			}
		};
		
		assertSame(ev,Cells.persist(ev, e1));

		// vector shouldn't be in other stores
		Hash hv=v.getHash();
		assertNull(e2.refForHash(hv));
		assertNull(m1.refForHash(hv));

		crossTest.accept(nv);
		crossTest.accept(ev);
		crossTest.accept(v);
		crossTest.accept(null);
	}

	private void doCrossStoreTest(ACell a, AStore s1, AStore s2) throws IOException {
		Hash ha=Cells.getHash(a);

		ACell a1=Cells.persist(a, s1);
		assertSame(a1,Cells.persist(a1,s1));

		ACell a2=Cells.persist(a1, s2);
		assertSame(a2,Cells.persist(a2,s2));

		assertNotNull(s2.refForHash(ha));
		assertEquals(a1,a2);
	}

	// ========== Cross-store lattice merge tests ==========

	@Test
	public void testLatticeMergeEtchToMemory() throws IOException {
		// Simulate lattice merge: read deep structure from Etch, persist to MemoryStore
		EtchStore source = EtchStore.createTemp();
		MemoryStore target = new MemoryStore();

		// Build and persist a nested structure in source
		AVector<ACell> inner = Vectors.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
		ASet<ACell> set = Sets.of(inner, CVMLong.create(42), Samples.NON_EMBEDDED_STRING);
		set = Cells.persist(set, source);
		Hash rootHash = Cells.getHash(set);

		// Persist to target store (lattice merge)
		ASet<ACell> merged = Cells.persist(set, target);

		// Verify in target
		Ref<?> tgtRef = target.refForHash(rootHash);
		assertNotNull(tgtRef, "Root should be retrievable from target after merge");
		assertTrue(tgtRef.getStatus() >= Ref.PERSISTED);
		assertEquals(set, merged);

		// Verify nested children are individually retrievable in target
		if (!Samples.NON_EMBEDDED_STRING.isEmbedded()) {
			Hash childHash = Cells.getHash(Samples.NON_EMBEDDED_STRING);
			assertNotNull(target.refForHash(childHash), "Non-embedded child should be in target");
		}
	}

	@Test
	public void testLatticeMergeMemoryToEtch() throws IOException {
		// Reverse direction: MemoryStore → EtchStore
		MemoryStore source = new MemoryStore();
		EtchStore target = EtchStore.createTemp();

		ASet<ACell> set = Sets.empty();
		for (int i = 0; i < 200; i++) {
			set = set.conj(CVMLong.create(i));
		}
		set = Cells.persist(set, source);
		Hash rootHash = Cells.getHash(set);

		// Merge to Etch
		Cells.persist(set, target);

		Ref<?> tgtRef = target.refForHash(rootHash);
		assertNotNull(tgtRef);
		assertTrue(tgtRef.getStatus() >= Ref.PERSISTED);
		assertEquals(200L, ((ASet<?>) tgtRef.getValue()).count());
	}

	@Test
	public void testBidirectionalLatticeMerge() throws IOException {
		// Two stores each have different data, merge both ways
		MemoryStore store1 = new MemoryStore();
		MemoryStore store2 = new MemoryStore();

		AVector<CVMLong> data1 = Vectors.of(1L, 2L, 3L);
		AVector<CVMLong> data2 = Vectors.of(4L, 5L, 6L);

		data1 = Cells.persist(data1, store1);
		data2 = Cells.persist(data2, store2);

		Hash h1 = Cells.getHash(data1);
		Hash h2 = Cells.getHash(data2);

		// store2 doesn't have data1, store1 doesn't have data2
		assertNull(store2.refForHash(h1));
		assertNull(store1.refForHash(h2));

		// Merge: each gets the other's data
		Cells.persist(data1, store2);
		Cells.persist(data2, store1);

		// Now both stores have both values
		assertNotNull(store1.refForHash(h2));
		assertNotNull(store2.refForHash(h1));
		assertEquals(data1, store2.refForHash(h1).getValue());
		assertEquals(data2, store1.refForHash(h2).getValue());
	}

	// ========== NullStore tests ==========

	@Test
	public void testNullStoreBasics() {
		NullStore ns = NullStore.INSTANCE;
		assertEquals("null", ns.shortName());

		// refForHash always returns null
		assertNull(ns.refForHash(Hash.EMPTY_HASH));
		assertNull(ns.refForHash(Samples.NON_EMBEDDED_STRING.getHash()));

		// checkCache always returns null
		assertNull(ns.checkCache(Hash.EMPTY_HASH));

		// getRootHash returns null
		assertNull(ns.getRootHash());
	}

	@Test
	public void testNullStoreStoreOps() throws IOException {
		NullStore ns = NullStore.INSTANCE;
		CVMLong val = CVMLong.create(42);
		Ref<CVMLong> ref = val.getRef();

		// storeRef returns the ref unchanged
		assertSame(ref, ns.storeRef(ref, Ref.STORED, null));
		assertSame(ref, ns.storeTopRef(ref, Ref.STORED, null));
	}

	@Test
	public void testNullStoreDecodeEmbedded() throws Exception {
		NullStore ns = NullStore.INSTANCE;

		// Decoding embedded values should work
		CVMLong val = CVMLong.create(42);
		Blob enc = val.getEncoding();
		ACell decoded = ns.decode(enc);
		assertEquals(val, decoded);
	}

	@Test
	public void testNullStoreRefMissing() {
		// RefSoft created with NullStore should throw MissingDataException on getValue
		Hash h = Samples.NON_EMBEDDED_STRING.getHash();
		Ref<ACell> ref = Ref.forHash(h, NullStore.INSTANCE);
		assertNotNull(ref);
		assertThrows(MissingDataException.class, () -> ref.getValue());
	}

	/**
	 * Test that peekResultID works even with no branches stored. We need to see the Result ID
	 * without assuming the message encoding is complete and with no store present.
	 * @throws Exception
	 */
	@Test
	public void testNullStorePeekResultID() throws Exception {
		// Create a Result with non-embedded content, encode as multi-cell
		Blob bigBlob = Blobs.createRandom(400);
		assertFalse(bigBlob.isEmbedded());
		Result result = Result.create(CVMLong.create(7), Vectors.of(bigBlob));

		Blob enc = Format.encodeMultiCell(result, true);

		// peekResultID should work without any store (uses NullStore internally)
		ACell id = Result.peekResultID(enc, 0);
		assertEquals(CVMLong.create(7), id);
	}

	// ========== Cross-store acquisition round-trip ==========

	@Test
	public void testCrossStoreIncrementalAcquisition() throws IOException {
		// Simulate Acquiror: persist deep structure in source, incrementally store in target
		EtchStore source = EtchStore.createTemp();
		MemoryStore target = new MemoryStore();

		// Build and persist a deep structure in source
		ASet<ACell> set = Sets.empty();
		for (int i = 0; i < 300; i++) {
			set = set.conj(CVMLong.create(i));
		}
		set = Cells.persist(set, source);
		Hash rootHash = Cells.getHash(set);

		// Verify fully persisted in source
		Ref<?> srcRef = source.refForHash(rootHash);
		assertNotNull(srcRef);
		assertTrue(srcRef.getStatus() >= Ref.PERSISTED);

		// Now store in target (simulating incremental acquisition)
		Cells.store(set, target);
		Ref<?> tgtStored = target.refForHash(rootHash);
		assertNotNull(tgtStored, "Root should be in target after store");

		// Persist fully in target
		Cells.persist(set, target);
		Ref<?> tgtPersisted = target.refForHash(rootHash);
		assertNotNull(tgtPersisted);
		assertTrue(tgtPersisted.getStatus() >= Ref.PERSISTED,
			"Should be fully persisted in target");

		// Verify value integrity
		assertEquals(300L, ((ASet<?>) tgtPersisted.getValue()).count());
	}
}
