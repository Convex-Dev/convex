package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.StoreTransfer;
import convex.etch.EtchStore;
import convex.etch.EtchUtils;
import convex.test.Samples;

/**
 * Tests for the store transfer primitives (StoreTransfer.transfer/verify,
 * EtchUtils.migrate) — see convex-core/docs/ETCH_GC.md.
 */
public class StoreTransferTest {

	/**
	 * Creates a distinct non-embedded string for the given seed.
	 */
	static AString nonEmbedded(int seed) {
		String base = "StoreTransfer test value " + seed + ". ";
		return Strings.create(base.repeat(1 + (150 / base.length())));
	}

	/**
	 * Branchy tree with non-embedded values at multiple depths.
	 */
	static AVector<ACell> tree(int seed) {
		return Vectors.of(
				nonEmbedded(seed),
				Vectors.of(nonEmbedded(seed + 1), CVMLong.create(seed)),
				Vectors.of(Vectors.of(nonEmbedded(seed + 2))));
	}

	@Test
	public void testTransferMemoryToEtch() throws IOException {
		MemoryStore src = new MemoryStore();
		AVector<ACell> v = Cells.persist(tree(10), src);
		Hash h = v.getHash();

		Ref<ACell> out = StoreTransfer.transfer(Samples.TEST_STORE, v.getRef());

		// Complete tree present in destination, nothing missing
		assertTrue(StoreTransfer.verify(Samples.TEST_STORE, h).isEmpty());

		// Returned ref bound to the destination, at the transferred status
		assertEquals(v, out.getValue());
		assertTrue(out.getStatus() >= Ref.PERSISTED);
		if (out instanceof RefSoft) {
			assertSame(Samples.TEST_STORE, ((RefSoft<?>) out).getStore());
		}
	}

	@Test
	public void testTransferEtchToMemoryLazy() throws IOException {
		AVector<ACell> v = Cells.persist(tree(20), Samples.TEST_STORE);
		Hash h = v.getHash();

		// Transfer starting from a hash-only ref resolved lazily via the source
		MemoryStore dest = new MemoryStore();
		Ref<ACell> lazy = RefSoft.createForHash(h, Samples.TEST_STORE);
		Ref<ACell> out = StoreTransfer.transfer(dest, lazy);

		assertTrue(StoreTransfer.verify(dest, h).isEmpty());
		assertEquals(v, out.getValue());
	}

	@Test
	public void testRepeatTransferIsNoOp() throws IOException {
		MemoryStore src = new MemoryStore();
		AVector<ACell> v = Cells.persist(tree(30), src);

		StoreTransfer.transfer(Samples.TEST_STORE2, v.getRef());
		long len = Samples.TEST_STORE2.getEtch().getDataLength();

		// Second transfer prunes on INV-1: no bytes written
		StoreTransfer.transfer(Samples.TEST_STORE2, v.getRef());
		assertEquals(len, Samples.TEST_STORE2.getEtch().getDataLength());
	}

	@Test
	public void testTransferPreservesRequestedStatus() throws IOException {
		MemoryStore src = new MemoryStore();
		AVector<ACell> v = Cells.persist(tree(40), src);
		Hash h = v.getHash();

		StoreTransfer.transfer(Samples.TEST_STORE2, v.getRef(), Ref.ANNOUNCED);

		// Root and branches recorded at ANNOUNCED in the destination file
		assertTrue(Samples.TEST_STORE2.getEtch().read(h).getStatus() >= Ref.ANNOUNCED);
		Hash branchHash = nonEmbedded(40).getHash();
		assertTrue(Samples.TEST_STORE2.getEtch().read(branchHash).getStatus() >= Ref.ANNOUNCED);
	}

	@Test
	public void testVerifyDetectsMissingChildren() throws IOException {
		MemoryStore src = new MemoryStore();
		AVector<ACell> v = Cells.persist(tree(50), src);
		Hash h = v.getHash();

		// STORED-level transfer into Etch: top entry only, no subtree claim.
		// (MemoryStore is different by design: it retains children even at
		// STORED level, so this test requires an Etch destination.)
		StoreTransfer.transfer(Samples.TEST_STORE2, v.getRef(), Ref.STORED);

		List<Hash> missing = StoreTransfer.verify(Samples.TEST_STORE2, h);
		assertTrue(missing.contains(nonEmbedded(50).getHash()));

		// Entirely absent tree: the root itself is missing
		Hash absent = nonEmbedded(59).getHash();
		assertEquals(List.of(absent), StoreTransfer.verify(Samples.TEST_STORE2, absent));
	}

	@Test
	public void testTransferStrictOnMissingSource() throws IOException {
		// Source holds the parent only (STORED, cold caches via reopen), so a
		// PERSISTED-level transfer must fail on the unresolvable child
		File f = File.createTempFile("transfer-missing", ".etch");
		f.deleteOnExit();

		AVector<ACell> parent = Vectors.of(nonEmbedded(60), CVMLong.create(1));
		Hash ph = parent.getHash();
		{
			EtchStore store = EtchStore.create(f);
			store.storeTopRef(parent.getRef(), Ref.STORED, null);
			store.flush();
			store.close();
		}

		EtchStore source = EtchStore.create(f);
		Ref<ACell> lazy = RefSoft.createForHash(ph, source);

		// Etch destination is strict: missing source data propagates
		assertThrows(MissingDataException.class,
				() -> StoreTransfer.transfer(Samples.TEST_STORE2, lazy));

		// MemoryStore destination is lenient by design (remote-acquisition
		// semantics): it stores what it can and caps the achieved status
		Ref<ACell> lazy2 = RefSoft.createForHash(ph, source);
		Ref<ACell> partial = StoreTransfer.transfer(new MemoryStore(), lazy2);
		assertTrue(partial.getStatus() < Ref.PERSISTED,
				"Partial transfer must not claim PERSISTED");
		source.close();
	}

	@Test
	public void testMigrateEverything() throws IOException {
		// Dedicated source store: migrate coverage is about exact store contents
		EtchStore source = EtchStore.createTemp("migrate-src");

		// Mixed contents: a persisted tree, an announced value, a STORED-only
		// parent (child deliberately absent), and an unreachable value
		AVector<ACell> persisted = Cells.persist(tree(70), source);
		AString announced = nonEmbedded(74);
		Cells.announce(announced, null, source);
		AVector<ACell> storedOnly = Vectors.of(nonEmbedded(75), CVMLong.create(2));
		source.storeTopRef(storedOnly.getRef(), Ref.STORED, null);
		AString unreachable = nonEmbedded(76);
		Cells.persist(unreachable, source);

		AStore dest = new MemoryStore();
		ACell destRoot = dest.getRootData();
		long count = EtchUtils.migrate(source, dest);
		assertTrue(count >= 6, "Expected all source entries processed, got " + count);

		// Everything from source is persisted in dest, at commensurate status
		assertTrue(StoreTransfer.verify(dest, persisted.getHash()).isEmpty());
		assertTrue(dest.refForHash(announced.getHash()).getStatus() >= Ref.ANNOUNCED);
		assertNotNull(dest.refForHash(storedOnly.getHash()));
		assertTrue(dest.refForHash(unreachable.getHash()).getStatus() >= Ref.PERSISTED);

		// Destination root untouched
		assertEquals(destRoot, dest.getRootData());
		source.close();
	}
}
