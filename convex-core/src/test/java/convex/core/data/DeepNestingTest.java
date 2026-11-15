package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;

/**
 * Values nested far deeper than a thread's stack allows recursion over, as CVM code can
 * build in a loop, are encoded and stored like any other value.
 */
public class DeepNestingTest {

	static final int DEEP=10000;

	/** A chain of single-entry vectors, optionally hashing each level as it is built. */
	static ACell chain(int depth, boolean hashAsBuilt) {
		return chain(CVMLong.ONE,depth,hashAsBuilt);
	}

	/** A chain of single-entry vectors over a value, optionally hashing each level as it is built. */
	static ACell chain(ACell base, int depth, boolean hashAsBuilt) {
		ACell c=base;
		for (int i=0; i<depth; i++) {
			c=Vectors.of(c);
			if (hashAsBuilt) c.getHash();
		}
		return c;
	}

	/** A chain whose every level holds the next level twice, so its paths double at each level. */
	static ACell sharedChain(int depth, boolean hashAsBuilt) {
		ACell c=CVMLong.ONE;
		for (int i=0; i<depth; i++) {
			c=Vectors.of(c,c,CVMLong.create(i));
			if (hashAsBuilt) c.getHash();
		}
		return c;
	}

	@Test
	public void testEmbeddedCheckOfDeepValue() {
		// The check measures each level's child first, down to the bottom
		assertEquals(chain(DEEP,true).isEmbedded(),chain(DEEP,false).isEmbedded());
		assertEquals(sharedChain(DEEP,true).isEmbedded(),sharedChain(DEEP,false).isEmbedded());
	}

	@Test
	public void testEncodingOfDeepValue() {
		ACell expected=chain(DEEP,true);
		assertEquals(expected.getEncodingLength(),chain(DEEP,false).getEncodingLength());
		ACell fresh=chain(DEEP,false);
		assertEquals(expected.getHash(),fresh.getHash());
		assertEquals(expected.getEncoding(),fresh.getEncoding());
		assertEquals(sharedChain(DEEP,true).getHash(),sharedChain(DEEP,false).getHash());
	}

	/** A deep value holding a part only by its hash encodes as it does with the part present. */
	@Test
	public void testEncodingOfDeepValueWithMissingPart() {
		Blob part=Blob.createRandom(new Random(1234),200); // a branch, in no store
		ACell expected=chain(Vectors.of(part),DEEP,true);
		AVector<ACell> holder=Vectors.of(part).updateRefs(r->Ref.forHash(r.getHash(),new MemoryStore()).markEmbedded(false));
		ACell fresh=chain(holder,DEEP,false);
		assertEquals(expected.getEncoding(),fresh.getEncoding());
		assertEquals(expected.getHash(),fresh.getHash());
	}

	@Test
	public void testPersistDeepValue() throws IOException {
		checkPersisted(new MemoryStore(),chain(DEEP,false),chain(DEEP,true));
		try (EtchStore etch=EtchStore.createTemp()) {
			checkPersisted(etch,chain(DEEP,false),chain(DEEP,true));
		}
	}

	/** A deep value read from one store, whose Refs carry that store's status, persists into another. */
	@Test
	public void testPersistDeepValueFromAnotherStore() throws IOException {
		try (EtchStore etch=EtchStore.createTemp()) {
			ACell stored=Cells.persist(chain(DEEP,false),etch);
			checkPersisted(new MemoryStore(),stored,chain(DEEP,true));
		}
	}

	@Test
	public void testPersistDeepSharedValue() throws IOException {
		checkPersisted(new MemoryStore(),sharedChain(3000,false),sharedChain(3000,true));
		try (EtchStore etch=EtchStore.createTemp()) {
			checkPersisted(etch,sharedChain(3000,false),sharedChain(3000,true));
		}
	}

	/**
	 * Persists a fresh value and checks every level of the chain is stored, and reported
	 * as new, exactly once.
	 */
	private static void checkPersisted(AStore store, ACell fresh, ACell expected) throws IOException {
		AtomicLong novelty=new AtomicLong();
		Ref<ACell> top=store.storeTopRef(Ref.get(fresh),Ref.PERSISTED,r->novelty.incrementAndGet());
		assertEquals(expected.getHash(),top.getHash());

		// Walk down the chain without recursion. A MemoryStore also stores and reports
		// the top cell when it is embedded.
		long branches=((store instanceof MemoryStore)&&expected.isEmbedded())?1:0;
		ACell c=expected;
		while (c.getRefCount()>0) {
			if (!c.isEmbedded()) {
				branches++;
				assertTrue(store.refForHash(c.getHash()).getStatus()>=Ref.PERSISTED);
			}
			c=c.getRef(0).getValue();
		}
		assertEquals(branches,novelty.get());
	}
}
