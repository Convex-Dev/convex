package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.InitTest;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.Stores;
import convex.core.util.ThreadUtils;
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
		AStore temp=Stores.current();
		try {
			Stores.setCurrent(testStore);

			// Use fresh State
			State s=InitTest.createState();
			Ref<State> sr=Cells.persist(s).getRef();

			Hash hash=sr.getHash();

			Ref<State> sr2=Ref.forHash(hash);
			State s2=sr2.getValue();
			s2.validate();
		} finally {
			Stores.setCurrent(temp);
		}
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
		
		AStore temp=Stores.current();
		try {
			Stores.setCurrent(e1);
			assertSame(ev,Cells.persist(ev));
			
			// vector shouldn't be in other stores
			Hash hv=v.getHash();
			assertNull(e2.refForHash(hv));
			assertNull(m1.refForHash(hv));
			
			crossTest.accept(nv);
			crossTest.accept(ev);
			crossTest.accept(v);
			crossTest.accept(null);
		} finally {
			Stores.setCurrent(temp);
		}
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

	// ========== ThreadUtils.runWithStore tests ==========

	@Test
	public void testRunWithStorePropagation() throws Exception {
		MemoryStore ms = new MemoryStore();
		CompletableFuture<AStore> observed = new CompletableFuture<>();

		ThreadUtils.runWithStore(ms, () -> {
			observed.complete(Stores.current());
		});

		AStore result = observed.get(5, TimeUnit.SECONDS);
		assertSame(ms, result, "Virtual thread should see the store passed to runWithStore");
	}

	@Test
	public void testRunWithStoreIsolation() throws Exception {
		MemoryStore ms1 = new MemoryStore();
		MemoryStore ms2 = new MemoryStore();
		CompletableFuture<AStore> obs1 = new CompletableFuture<>();
		CompletableFuture<AStore> obs2 = new CompletableFuture<>();

		// Launch two concurrent virtual threads with different stores
		ThreadUtils.runWithStore(ms1, () -> {
			try { Thread.sleep(10); } catch (InterruptedException e) {}
			obs1.complete(Stores.current());
		});
		ThreadUtils.runWithStore(ms2, () -> {
			try { Thread.sleep(10); } catch (InterruptedException e) {}
			obs2.complete(Stores.current());
		});

		assertSame(ms1, obs1.get(5, TimeUnit.SECONDS), "Thread 1 should see store 1");
		assertSame(ms2, obs2.get(5, TimeUnit.SECONDS), "Thread 2 should see store 2");
	}

	@Test
	public void testRunWithStoreRestoresOnCompletion() throws Exception {
		AStore before = Stores.current();

		MemoryStore ms = new MemoryStore();
		CompletableFuture<Void> done = new CompletableFuture<>();
		ThreadUtils.runWithStore(ms, () -> done.complete(null));
		done.get(5, TimeUnit.SECONDS);

		// Calling thread's store should be unaffected
		assertSame(before, Stores.current(), "Calling thread store should not change");
	}

	// ========== Cross-store acquisition round-trip ==========

	@Test
	public void testCrossStoreIncrementalAcquisition() throws IOException {
		// Simulate Acquiror: persist deep structure in source, incrementally store in target
		EtchStore source = EtchStore.createTemp();
		MemoryStore target = new MemoryStore();

		AStore saved = Stores.current();
		try {
			// Build and persist a deep structure in source
			Stores.setCurrent(source);
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
			Stores.setCurrent(target);
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
		} finally {
			Stores.setCurrent(saved);
		}
	}
}
