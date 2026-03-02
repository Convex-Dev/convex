package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.generic.LWWLattice;

/**
 * Tests for the LocalLattice convention (:local OwnerLattice).
 *
 * Verifies per-peer isolation, merge semantics, EtchStore round-trip,
 * and nested structure navigation.
 */
public class LocalLatticeTest {

	private static final Keyword KEY_SIGNING = Keyword.intern("signing");
	private static final Keyword KEY_SECRET = Keyword.intern("secret");
	private static final Keyword KEY_VERSION = Keyword.intern("version");

	// ===== Basic Read/Write =====

	@Test
	public void testCreateAndReadSlot() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		AHashMap<Keyword, ACell> data = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, data);

		// Read back the slot
		AHashMap<Keyword, ACell> read = LocalLattice.getSlot(local, pk);
		assertNotNull(read);
		assertEquals(CVMLong.create(1), read.get(KEY_VERSION));
	}

	@Test
	public void testGetSubKey() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		AHashMap<Keyword, ACell> data = Maps.of(
				KEY_VERSION, CVMLong.create(42),
				KEY_SECRET, Strings.create("encrypted-blob")
		);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, data);

		assertEquals(CVMLong.create(42), LocalLattice.get(local, pk, KEY_VERSION));
		assertEquals(Strings.create("encrypted-blob"), LocalLattice.get(local, pk, KEY_SECRET));
		assertNull(LocalLattice.get(local, pk, KEY_SIGNING));
	}

	@Test
	public void testSetSlotUpdates() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		AHashMap<Keyword, ACell> data1 = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, data1);

		// Update with new data
		AHashMap<Keyword, ACell> data2 = Maps.of(KEY_VERSION, CVMLong.create(2));
		local = LocalLattice.setSlot(local, kp, data2);

		assertEquals(CVMLong.create(2), LocalLattice.get(local, pk, KEY_VERSION));
	}

	@Test
	public void testSetSlotOnNull() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		AHashMap<Keyword, ACell> data = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.setSlot(null, kp, data);

		assertNotNull(local);
		assertEquals(CVMLong.create(1), LocalLattice.get(local, pk, KEY_VERSION));
	}

	@Test
	public void testGetSlotFromNull() {
		AKeyPair kp = AKeyPair.generate();
		assertNull(LocalLattice.getSlot(null, kp.getAccountKey()));
		assertNull(LocalLattice.getSignedSlot(null, kp.getAccountKey()));
		assertNull(LocalLattice.get(null, kp.getAccountKey(), KEY_VERSION));
	}

	@Test
	public void testGetNonexistentPeer() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();

		AHashMap<Keyword, ACell> data = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp1, data);

		// kp2 has no entry
		assertNull(LocalLattice.getSlot(local, kp2.getAccountKey()));
	}

	// ===== Two Peer Isolation =====

	@Test
	public void testTwoPeersIndependentSlots() {
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();

		AHashMap<Keyword, ACell> dataA = Maps.of(KEY_VERSION, CVMLong.create(10));
		AHashMap<Keyword, ACell> dataB = Maps.of(KEY_VERSION, CVMLong.create(20));

		// Peer A writes
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kpA, dataA);
		// Peer B writes to same map
		local = LocalLattice.setSlot(local, kpB, dataB);

		// Both slots independently readable
		assertEquals(CVMLong.create(10), LocalLattice.get(local, kpA.getAccountKey(), KEY_VERSION));
		assertEquals(CVMLong.create(20), LocalLattice.get(local, kpB.getAccountKey(), KEY_VERSION));
	}

	// ===== Merge Tests =====

	@Test
	public void testMergeTwoPeersDifferentKeys() {
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();

		AHashMap<Keyword, ACell> dataA = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<Keyword, ACell> dataB = Maps.of(KEY_VERSION, CVMLong.create(2));

		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localA = LocalLattice.createSlot(kpA, dataA);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localB = LocalLattice.createSlot(kpB, dataB);

		// Merge — both peers' slots should be preserved
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> merged = LocalLattice.LATTICE.merge(localA, localB);

		assertNotNull(merged);
		assertEquals(CVMLong.create(1), LocalLattice.get(merged, kpA.getAccountKey(), KEY_VERSION));
		assertEquals(CVMLong.create(2), LocalLattice.get(merged, kpB.getAccountKey(), KEY_VERSION));
	}

	@Test
	public void testMergeWithNull() {
		AKeyPair kp = AKeyPair.generate();
		AHashMap<Keyword, ACell> data = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, data);

		// Merge with null preserves existing
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> merged = LocalLattice.LATTICE.merge(local, null);
		assertEquals(CVMLong.create(1), LocalLattice.get(merged, kp.getAccountKey(), KEY_VERSION));

		// Merge null with value returns value
		merged = LocalLattice.LATTICE.merge(null, local);
		assertEquals(CVMLong.create(1), LocalLattice.get(merged, kp.getAccountKey(), KEY_VERSION));
	}

	@Test
	public void testMergeSamePeerLWW() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		// Peer writes :signing with timestamp 100
		AHashMap<Keyword, ACell> signing1 = Maps.of(
				KEY_VERSION, CVMLong.create(1),
				LWWLattice.KEY_TIMESTAMP, CVMLong.create(100));
		AHashMap<Keyword, ACell> data1 = Maps.of(KEY_SIGNING, signing1);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local1 = LocalLattice.createSlot(kp, data1);

		// Same peer writes :signing with timestamp 200
		AHashMap<Keyword, ACell> signing2 = Maps.of(
				KEY_VERSION, CVMLong.create(2),
				LWWLattice.KEY_TIMESTAMP, CVMLong.create(200));
		AHashMap<Keyword, ACell> data2 = Maps.of(KEY_SIGNING, signing2);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local2 = LocalLattice.createSlot(kp, data2);

		// Context-aware merge — higher timestamp should win
		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> merged = LocalLattice.LATTICE.merge(ctx, local1, local2);

		AHashMap<Keyword, ACell> slot = LocalLattice.getSlot(merged, pk);
		assertNotNull(slot);
		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> mergedSigning = (AHashMap<Keyword, ACell>) slot.get(KEY_SIGNING);
		assertEquals(CVMLong.create(2), mergedSigning.get(KEY_VERSION),
				"Higher timestamp (200) should win");
	}

	@Test
	public void testMergeSamePeerLWWReversed() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		// Same as above but merge in reverse order — result must be identical
		AHashMap<Keyword, ACell> signing1 = Maps.of(
				KEY_VERSION, CVMLong.create(1),
				LWWLattice.KEY_TIMESTAMP, CVMLong.create(100));
		AHashMap<Keyword, ACell> data1 = Maps.of(KEY_SIGNING, signing1);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local1 = LocalLattice.createSlot(kp, data1);

		AHashMap<Keyword, ACell> signing2 = Maps.of(
				KEY_VERSION, CVMLong.create(2),
				LWWLattice.KEY_TIMESTAMP, CVMLong.create(200));
		AHashMap<Keyword, ACell> data2 = Maps.of(KEY_SIGNING, signing2);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local2 = LocalLattice.createSlot(kp, data2);

		LatticeContext ctx = LatticeContext.create(null, kp);

		// Merge both orders — must be commutative
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> mergedAB = LocalLattice.LATTICE.merge(ctx, local1, local2);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> mergedBA = LocalLattice.LATTICE.merge(ctx, local2, local1);

		assertEquals(LocalLattice.getSlot(mergedAB, pk), LocalLattice.getSlot(mergedBA, pk),
				"Merge must be commutative");
	}

	@Test
	public void testMergeIndependentServices() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		Keyword KEY_OTHER = Keyword.intern("other");

		// State A: only :signing
		AHashMap<Keyword, ACell> signingData = Maps.of(
				KEY_VERSION, CVMLong.create(1),
				LWWLattice.KEY_TIMESTAMP, CVMLong.create(100));
		AHashMap<Keyword, ACell> dataA = Maps.of(KEY_SIGNING, signingData);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localA = LocalLattice.createSlot(kp, dataA);

		// State B: only :other
		AHashMap<Keyword, ACell> otherData = Maps.of(
				KEY_VERSION, CVMLong.create(42),
				LWWLattice.KEY_TIMESTAMP, CVMLong.create(150));
		AHashMap<Keyword, ACell> dataB = Maps.of(KEY_OTHER, otherData);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localB = LocalLattice.createSlot(kp, dataB);

		// Merge — both services should survive
		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> merged = LocalLattice.LATTICE.merge(ctx, localA, localB);

		AHashMap<Keyword, ACell> slot = LocalLattice.getSlot(merged, pk);
		assertNotNull(slot);
		assertNotNull(slot.get(KEY_SIGNING), ":signing should survive merge");
		assertNotNull(slot.get(KEY_OTHER), ":other should survive merge");
	}

	@Test
	public void testMergeForeignPeerRejectedByContext() {
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();

		AHashMap<Keyword, ACell> data = Maps.of(KEY_VERSION, CVMLong.create(99));

		// Peer B signs data, but claims to be peer A's slot
		SignedData<AHashMap<Keyword, ACell>> signedByB = kpB.signData(data);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> fakeLocal =
				Maps.of(kpA.getAccountKey(), signedByB);

		// Peer A has legitimate data
		AHashMap<Keyword, ACell> dataA = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> localA = LocalLattice.createSlot(kpA, dataA);

		// Context-aware merge should reject the forged entry
		LatticeContext ctx = LatticeContext.create(null, kpA);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> merged = LocalLattice.LATTICE.merge(ctx, localA, fakeLocal);

		// Peer A's legitimate data should remain
		assertEquals(CVMLong.create(1), LocalLattice.get(merged, kpA.getAccountKey(), KEY_VERSION));
	}

	// ===== EtchStore Round-Trip =====

	@Test
	public void testEtchStoreRoundTrip() throws IOException {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		// Build local data with nested structure
		AHashMap<Keyword, ACell> data = Maps.of(
				KEY_VERSION, CVMLong.create(3),
				KEY_SIGNING, Maps.of(KEY_SECRET, Strings.create("encrypted-data"))
		);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, data);

		// Persist to EtchStore
		EtchStore store = EtchStore.createTemp("local-lattice-test");
		try {
			store.setRootData(local);
			store.flush();

			File etchFile = store.getFile();
			store.close();

			// Reopen and read back
			EtchStore reopened = EtchStore.create(etchFile);
			try {
				@SuppressWarnings("unchecked")
				AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> loaded =
						(AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>>) reopened.getRootData();

				assertNotNull(loaded);
				assertEquals(CVMLong.create(3), LocalLattice.get(loaded, pk, KEY_VERSION));

				// Verify nested structure
				AHashMap<Keyword, ACell> slot = LocalLattice.getSlot(loaded, pk);
				assertNotNull(slot);
				ACell signing = slot.get(KEY_SIGNING);
				assertNotNull(signing);
				@SuppressWarnings("unchecked")
				AHashMap<Keyword, ACell> signingMap = (AHashMap<Keyword, ACell>) signing;
				assertEquals(Strings.create("encrypted-data"), signingMap.get(KEY_SECRET));
			} finally {
				reopened.close();
			}
		} catch (Exception e) {
			store.close();
			throw e;
		}
	}

	// ===== Nested Structure Navigation =====

	@Test
	public void testNestedIndexStructure() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		// Build a nested structure simulating the signing service layout:
		// :signing → { :secret → blob, :version → 1 }
		AHashMap<Keyword, ACell> signingData = Maps.of(
				KEY_SECRET, Strings.create("aes-gcm-encrypted-secret"),
				KEY_VERSION, CVMLong.create(1)
		);
		AHashMap<Keyword, ACell> peerData = Maps.of(KEY_SIGNING, signingData);
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, peerData);

		// Navigate: local → peer slot → :signing → :secret
		AHashMap<Keyword, ACell> slot = LocalLattice.getSlot(local, pk);
		assertNotNull(slot);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> signing = (AHashMap<Keyword, ACell>) slot.get(KEY_SIGNING);
		assertNotNull(signing);
		assertEquals(Strings.create("aes-gcm-encrypted-secret"), signing.get(KEY_SECRET));
		assertEquals(CVMLong.create(1), signing.get(KEY_VERSION));
	}

	@Test
	public void testSignedSlotPreservesSignature() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey pk = kp.getAccountKey();

		AHashMap<Keyword, ACell> data = Maps.of(KEY_VERSION, CVMLong.create(1));
		AHashMap<ACell, SignedData<AHashMap<Keyword, ACell>>> local = LocalLattice.createSlot(kp, data);

		// Verify the signed data is properly signed
		SignedData<AHashMap<Keyword, ACell>> signed = LocalLattice.getSignedSlot(local, pk);
		assertNotNull(signed);
		assertEquals(pk, signed.getAccountKey());
		assertTrue(signed.checkSignature());
	}

	// ===== Constants =====

	@Test
	public void testKeyLocalConstant() {
		assertEquals("local", LocalLattice.KEY_LOCAL.getName().toString());
	}

	@Test
	public void testLatticeInstance() {
		assertNotNull(LocalLattice.LATTICE);
		assertNotNull(LocalLattice.getLattice());
		assertSame(LocalLattice.LATTICE, LocalLattice.getLattice());
	}
}
