package convex.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.Test;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Init;
import convex.core.Order;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.Symbols;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;
import convex.core.util.Utils;
import convex.test.Samples;
import etch.EtchStore;

public class EtchStoreTest {

	private static final Hash BAD_HASH = Samples.BAD_HASH;
	private EtchStore store = EtchStore.createTemp();

	@Test
	public void testEmptyStore() {
		AStore oldStore = Stores.current();
		try {
			Stores.setCurrent(store);
			assertTrue(oldStore != store);
			assertEquals(store, Stores.current());

			assertNull(store.refForHash(BAD_HASH));

			AMap<String, String> data = Maps.of(Keywords.FOO,Symbols.FOO);
			Ref<AMap<String, String>> goodRef = data.getRef();
			Hash goodHash = goodRef.getHash();
			assertNull(store.refForHash(goodHash));

			goodRef.persist();

			if (!data.isEmbedded()) {
				Ref<AMap<String, String>> recRef = store.refForHash(goodHash);
				assertNotNull(recRef);
	
				assertEquals(data, recRef.getValue());
			}
		} finally {
			Stores.setCurrent(oldStore);
		}
	}

	@Test
	public void testPersistedStatus() throws BadFormatException {
		AStore oldStore = Stores.current();
		try {
			Stores.setCurrent(store);

			// generate Hash of unique secure random bytes to test - should not already be
			// in store
			Blob randomBlob = Blob.createRandom(new Random(), Format.MAX_EMBEDDED_LENGTH+1);
			Hash hash = randomBlob.getHash();
			assertNotEquals(hash, randomBlob);

			Ref<Blob> initialRef = randomBlob.getRef();
			assertEquals(Ref.UNKNOWN, initialRef.getStatus());
			assertNull(Stores.current().refForHash(hash));
			
			// shallow persistence first
			Ref<Blob> refShallow=initialRef.persistShallow();
			assertEquals(Ref.STORED, refShallow.getStatus());
			
			Ref<Blob> ref = initialRef.persist();
			assertEquals(Ref.PERSISTED, ref.getStatus());
			assertTrue(ref.isPersisted());

			Ref<Blob> newRef = Stores.current().refForHash(hash);
			assertEquals(initialRef, newRef);
			assertEquals(randomBlob, newRef.getValue());
		} finally {
			Stores.setCurrent(oldStore);
		}
	}
	
	@Test
	public void testBeliefAnnounce() {
		AStore oldStore = Stores.current();
		AtomicLong counter=new AtomicLong(0L);
		
		AKeyPair kp=Init.HERO_KP;
		try {
			Stores.setCurrent(store);
			
			ATransaction t1=Invoke.create(0, Lists.of(Symbols.PLUS, Symbols.STAR_BALANCE, 1000L));
			ATransaction t2=Transfer.create(1, Init.VILLAIN,1000000);
			Block b=Block.of(Utils.getCurrentTimestamp(),kp.signData(t1),kp.signData(t2));
			Order ord=Order.create().propose(b);
			
			Belief belief=Belief.create(kp,ord);
			
			Ref<Belief> rb=belief.getRef();
			Ref<ATransaction> rt=t1.getRef();
			assertEquals(Ref.UNKNOWN,rb.getStatus());
			assertEquals(Ref.UNKNOWN,rt.getStatus());
			
			assertEquals(3,Utils.refCount(t1));
			assertEquals(0,Utils.refCount(t2));
			assertEquals(11,Utils.totalRefCount(belief));
			

			Consumer<Ref<ACell>> noveltyHandler=r-> {
				counter.incrementAndGet();
			};

			// First try shallow persistence
			counter.set(0L);
			Ref<Belief> srb=rb.persistShallow(noveltyHandler);
			assertEquals(Ref.STORED,srb.getStatus());
			assertEquals(1L,counter.get()); // One cell persisted
			
			// assertEquals(srb,store.refForHash(rb.getHash()));
			assertNull(store.refForHash(t1.getRef().getHash()));
			
			// Persist belief
			counter.set(0L);
			Ref<Belief> prb=srb.persist(noveltyHandler);
			assertEquals(2L,counter.get());
			
			// Persist again. Should be no new novelty
			counter.set(0L);
			Ref<Belief> prb2=srb.persist(noveltyHandler);
			assertEquals(prb2,prb);
			assertEquals(0L,counter.get()); // Nothing new persisted
			
			// Announce belief
			counter.set(0L);
			Ref<Belief> arb=srb.announce(noveltyHandler);
			assertEquals(srb,arb);
			assertEquals(2L,counter.get()); 
			
			// Announce again. Should be no new novelty
			counter.set(0L);
			Ref<Belief> arb2=srb.announce(noveltyHandler);
			assertEquals(srb,arb2);
			assertEquals(0L,counter.get()); // Nothing new announced
			
			// Check re-stored ref has correct status
			counter.set(0L);
			Ref<Belief> arb3=srb.persistShallow(noveltyHandler);
			assertEquals(0L,counter.get()); // Nothing new persisted
			assertTrue(Ref.STORED<=arb3.getStatus());
			
			if (!belief.isEmbedded()) {
				// Recover Belief from store
				Belief recb=(Belief) store.refForHash(belief.getHash()).getValue();
				assertEquals(belief,recb);
			}
		} finally {
			Stores.setCurrent(oldStore);
		}
	}

	@Test
	public void testNoveltyHandler() {
		AStore oldStore = Stores.current();
		ArrayList<Ref<ACell>> al = new ArrayList<>();
		try {
			Stores.setCurrent(store);
			AVector<Integer> data = Samples.INT_VECTOR_300;

			// handler that records added refs
			Consumer<Ref<ACell>> handler = r -> al.add(r);

			Ref<AVector<Integer>> dataRef = data.getRef();
			Hash dataHash = dataRef.getHash();
			assertNull(store.refForHash(dataHash));

			dataRef.persist(handler);
			int num=al.size(); // number of novel cells persisted
			assertTrue(num>0); // got new novelty
			assertEquals(data, al.get(num-1).getValue());

			Samples.INT_VECTOR_300.getRef().persist();
			assertEquals(num, al.size()); // no new novelty transmitted
		} finally {
			Stores.setCurrent(oldStore);
		}
	}
	
	@Test
	public void testReopen() throws IOException {
		File file=File.createTempFile("etch",null);
		EtchStore es=EtchStore.create(file);
		es.setRootHash(Hash.NULL_HASH);
		es.close();
		
		EtchStore es2=EtchStore.create(file);
		assertEquals(Hash.NULL_HASH,es2.getRootHash());
	}
}
