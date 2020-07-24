package convex.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
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
import convex.core.data.Blob;
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

			AMap<String, String> data = Maps.of("foo", "bar3621863168");
			Ref<AMap<String, String>> goodRef = Ref.create(data);
			Hash goodHash = goodRef.getHash();
			assertNull(store.refForHash(goodHash));

			goodRef.persist();

			Ref<AMap<String, String>> recRef = store.refForHash(goodHash);
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
			Stores.setCurrent(store);
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
			
			// shallow persistence first
			Ref<Blob> refShallow=initialRef.persistShallow();
			assertEquals(Ref.STORED, refShallow.getStatus());
			
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
	public void testBeliefAnnounce() {
		AStore oldStore = Stores.current();
		AtomicLong counter=new AtomicLong(0L);
		
		AKeyPair kp=Init.HERO_KP;
		try {
			Stores.setCurrent(store);
			
			ATransaction t1=Invoke.create(0, Lists.of(Symbols.PLUS, Symbols.STAR_BALANCE, 1000L));
			ATransaction t2=Transfer.create(1, Init.VILLAIN,1000000);
			Block b=Block.of(Utils.getCurrentTimestamp(),kp.signData(t1),kp.signData(t2));
			Blob blockEncoding=b.getEncoding();
			Hash blockHash=blockEncoding.getContentHash();
			Order ord=Order.create().propose(b);
			
			Belief belief=Belief.create(kp,ord);
			
			Ref<Belief> rb=Ref.create(belief);
			Ref<ATransaction> rt=Ref.create(t1);
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
			assertEquals(1L,counter.get()); // Exactly one ref should be stored
			
			assertEquals(srb,store.refForHash(rb.getHash()));
			assertNull(store.refForHash(Ref.create(t1).getHash()));
			
			
			// Persist belief
			counter.set(0L);
			Ref<Belief> prb=srb.persist(noveltyHandler);
			assertEquals(8L,counter.get()); // 11 Refs minus 3 embedded Refs
			
			// Persist again. Should be no new novelty
			counter.set(0L);
			Ref<Belief> prb2=srb.persist(noveltyHandler);
			assertEquals(prb2,prb);
			assertEquals(0L,counter.get()); // Nothing new persisted
			
			// Announce belief
			counter.set(0L);
			Ref<Belief> arb=srb.announce(noveltyHandler);
			assertEquals(srb,arb);
			assertEquals(8L,counter.get()); // 11 Refs minus 3 embedded Refs
			
			// Announce again. Should be no new novelty
			counter.set(0L);
			Ref<Belief> arb2=srb.announce(noveltyHandler);
			assertEquals(srb,arb2);
			assertEquals(0L,counter.get()); // Nothing new announced
			
			// Check re-persisted ref has correct status
			counter.set(0L);
			Ref<Belief> arb3=srb.persistShallow(noveltyHandler);
			assertEquals(0L,counter.get()); // Nothing new persisted
			assertEquals(Ref.ANNOUNCED,arb3.getStatus());
			
			// test block hash encoding
			assertEquals(blockHash,ord.getChildRefs()[0].getHash());
			Ref<Block> blockRef=store.refForHash(blockHash);
			assertEquals(blockHash,blockRef.getHash());
			
			// Recover Belief from store
			Belief recb=(Belief) store.refForHash(belief.getHash()).getValue();
			assertEquals(belief,recb);
			
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
			Object data = Samples.INT_VECTOR_10;

			// handler that records added refs
			Consumer<Ref<ACell>> handler = r -> al.add(r);

			Ref<Object> dataRef = Ref.create(data);
			Hash dataHash = dataRef.getHash();
			assertNull(store.refForHash(dataHash));

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
