package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Syntax;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Do;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.test.Samples;

/**
 * updateRefs returns the same value held through different refs. What is cached about
 * the value travels with the rebuilt cell; the cached ref, whose status is the caller's
 * to decide, does not. The stores, which change the refs, finish the rebuilt cell with
 * a ref of their own.
 */
public class UpdateRefsTest {
	/** Replaces every ref with a fresh direct one, so every cell holding a ref is rebuilt */
	static final IRefFunction FRESH=r->RefDirect.create(r.getValue());

	static ACell[] cellsWithRefs() {
		return new ACell[] {
			Samples.LONG_MAP_100, Samples.LONG_MAP_5,
			Samples.INT_SET_300, Samples.INT_SET_10,
			Samples.INT_INDEX_256,
			Samples.INT_VECTOR_256, Samples.INT_VECTOR_300,
			Samples.BIG_BLOB_TREE, Samples.MIN_TREE_STRING,
			Samples.INT_LIST_300,
			Syntax.create(Vectors.of(1,2,3), Maps.of(Keyword.create("doc"), Strings.create("meta"))),
			Do.create(Constant.create(CVMLong.create(1)), Constant.create(Vectors.of(1,2))),
			Samples.INVOKE_TRANSACTION, Samples.EMPTY_ORDER,
			MapEntry.create(CVMLong.create(1), Vectors.of(1,2)),
		};
	}

	@Test
	public void testCarriesValueCaches() {
		for (ACell a : cellsWithRefs()) {
			String cls=a.getClass().getSimpleName();
			assertTrue(a.getRefCount()>0, cls);
			Blob encoding=a.getEncoding();
			long size=a.getMemorySize();
			ACell b=a.updateRefs(FRESH);
			assertNotSame(a, b, cls);
			assertEquals(a, b, cls);
			assertSame(encoding, b.cachedEncoding(), cls);
			assertEquals(size, b.memorySize, cls);
			assertNull(b.cachedRef, cls);
		}
	}

	@Test
	public void testLeavesUnchanged() {
		ACell[] leaves= {CVMLong.create(7), Keyword.create("k"), Blob.fromHex("cafe"),
			Strings.create("short"), Address.create(13), Samples.NON_EMBEDDED_BLOB};
		for (ACell a : leaves) assertSame(a, a.updateRefs(FRESH));
	}

	@Test
	public void testRebuiltCellHasNoStatusClaim() throws IOException {
		AVector<CVMLong> a=Cells.persist(Vectors.of(1,2,3), new MemoryStore());
		assertTrue(a.getRef().getStatus()>=Ref.PERSISTED);
		AVector<CVMLong> b=a.updateRefs(FRESH);
		assertNotSame(a, b);
		assertEquals(Ref.UNKNOWN, b.getRef().getStatus());
	}

	@Test
	public void testSignedDataKeepsVerification() throws IOException {
		SignedData<ACell> sd=Samples.KEY_PAIR.signData(Vectors.of(1,2,3));
		assertTrue(sd.checkSignature());
		assertTrue(sd.isSignatureChecked());
		SignedData<ACell> nsd=sd.updateRefs(FRESH);
		assertNotSame(sd, nsd);
		assertTrue(nsd.isSignatureChecked()); // the check is a fact about the value
		assertEquals(Ref.UNKNOWN, nsd.getRef().getStatus()); // the status is not
		checkVerifiedAfterPersist(nsd, new MemoryStore());
		try (EtchStore store=EtchStore.createTemp("updaterefs")) {
			checkVerifiedAfterPersist(nsd, store);
		}
	}

	static void checkVerifiedAfterPersist(SignedData<ACell> sd, AStore store) throws IOException {
		String s=store.getClass().getSimpleName();
		SignedData<ACell> psd=Cells.persist(sd, store);
		assertNotSame(sd, psd, s);
		assertTrue(psd.isSignatureChecked(), s);
		assertTrue(psd.getRef().getStatus()>=Ref.PERSISTED, s);
	}

	@Test
	public void testStoresFinishRebuiltCells() throws IOException {
		AHashMap<CVMLong,CVMLong> m=Maps.empty();
		for (int i=0; i<300; i++) m=m.assoc(CVMLong.create(i), CVMLong.create(i*2));
		long size=m.getMemorySize(); // as an inbound size check does before persisting
		checkFinished(m, size, new MemoryStore());
		try (EtchStore store=EtchStore.createTemp("updaterefs")) {
			checkFinished(m, size, store);
		}
	}

	/** After persisting, the rebuilt root and children hold their size and the store's ref */
	static void checkFinished(ACell a, long size, AStore store) throws IOException {
		String s=store.getClass().getSimpleName();
		ACell p=Cells.persist(a, store);
		assertNotSame(a, p, s);
		assertEquals(size, p.memorySize, s);
		Ref<?> ref=p.cachedRef;
		assertNotNull(ref, s);
		assertTrue(ref.getStatus()>=Ref.PERSISTED, s);
		assertNotNull(Cells.cachedHash(p), s);
		int n=p.getRefCount();
		for (int i=0; i<n; i++) {
			ACell child=p.getRef(i).getValue();
			assertTrue(child.memorySize>=0, s);
			Ref<?> cr=child.cachedRef;
			assertNotNull(cr, s);
			assertTrue(cr.getStatus()>=Ref.PERSISTED, s);
		}
	}

	@Test
	public void testAttachRefKeepsValueFacts() {
		ACell a=Vectors.of(1,2,3);
		Ref<ACell> first=a.getRef();
		first.setFlags(Ref.KNOWN_EMBEDDED_MASK);
		a.attachRef(RefDirect.create(a, null, Ref.UNKNOWN)); // no higher status: ignored
		assertSame(first, a.getRef());
		Ref<ACell> stored=RefDirect.create(a, null, Ref.STORED);
		a.attachRef(stored);
		assertSame(stored, a.getRef());
		assertEquals(Ref.STORED|Ref.KNOWN_EMBEDDED_MASK, stored.getFlags());
	}
}
