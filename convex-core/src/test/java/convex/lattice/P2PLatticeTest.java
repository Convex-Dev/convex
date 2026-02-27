package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Tests for P2PLattice — the {@code :p2p} lattice region for node discovery.
 *
 * Verifies NodeInfo creation, LWW merge semantics, OwnerLattice signature
 * verification, ROOT path navigation, and multi-node convergence.
 */
public class P2PLatticeTest {

	private static final AString TEST_TYPE = Strings.create("Convex Test Node");

	// ===== NodeInfo creation and reading =====

	@Test
	public void testNodeInfoCreateAndRead() {
		AKeyPair kp = AKeyPair.generate();

		AHashMap<Keyword, ACell> info = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://peer.example.com:18888")),
			TEST_TYPE,
			Strings.create("0.8.3"),
			Vectors.of(Strings.create("eu-west")),
			1000L
		);

		// Verify all fields
		assertEquals(CVMLong.create(1000), info.get(Keywords.TIMESTAMP));
		assertEquals(TEST_TYPE, info.get(Keywords.TYPE));
		assertEquals(Strings.create("0.8.3"), info.get(Keywords.VERSION));
		assertEquals(Strings.create("eu-west"), ((AVector<?>) info.get(Keywords.REGIONS)).get(0));
		assertEquals(Strings.create("tcp://peer.example.com:18888"),
			((AVector<?>) info.get(Keywords.TRANSPORTS)).get(0));

		// Sign and read back
		AHashMap<ACell, SignedData<ACell>> entry = P2PLattice.createSignedEntry(kp, info);
		AHashMap<Keyword, ACell> readBack = P2PLattice.getNodeInfo(entry, kp.getAccountKey());
		assertEquals(info, readBack);
	}

	@Test
	public void testNodeInfoWithoutRegions() {
		AHashMap<Keyword, ACell> info = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://peer.example.com:18888")),
			TEST_TYPE, Strings.create("0.8.3"),
			null,
			1000L
		);

		assertNull(info.get(Keywords.REGIONS));
		assertNotNull(info.get(Keywords.TRANSPORTS));
		assertNotNull(info.get(Keywords.TYPE));
		assertNotNull(info.get(Keywords.VERSION));
		assertNotNull(info.get(Keywords.TIMESTAMP));
	}

	@Test
	public void testNodeInfoTimestampDefault() {
		long before = System.currentTimeMillis();
		AHashMap<Keyword, ACell> info = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://peer.example.com:18888")),
			TEST_TYPE, Strings.create("0.8.3"),
			null
		);
		long after = System.currentTimeMillis();

		long ts = ((CVMLong) info.get(Keywords.TIMESTAMP)).longValue();
		assertTrue(ts >= before && ts <= after, "Timestamp should be around now");
	}

	// ===== LWW merge =====

	@Test
	public void testLWWMergeHigherTimestampWins() {
		AHashMap<Keyword, ACell> old = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://host:1")),
			TEST_TYPE, Strings.create("0.8.2"),
			null, 1000L
		);
		AHashMap<Keyword, ACell> newer = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://host:2")),
			TEST_TYPE, Strings.create("0.8.3"),
			null, 2000L
		);

		// LWW should pick the newer value
		ACell merged = LWWLattice.INSTANCE.merge(old, newer);
		assertEquals(newer, merged);

		// Commutative
		ACell merged2 = LWWLattice.INSTANCE.merge(newer, old);
		assertEquals(newer, merged2);
	}

	@Test
	public void testLWWMergeEqualTimestamp() {
		AHashMap<Keyword, ACell> a = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://host:1")),
			TEST_TYPE, Strings.create("v1"),
			null, 1000L
		);
		AHashMap<Keyword, ACell> b = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://host:2")),
			TEST_TYPE, Strings.create("v2"),
			null, 1000L
		);

		// Equal timestamps — prefer own value
		ACell mergeAB = LWWLattice.INSTANCE.merge(a, b);
		ACell mergeBA = LWWLattice.INSTANCE.merge(b, a);
		assertSame(a, mergeAB, "Should prefer own value");
		assertSame(b, mergeBA, "Should prefer own value");
	}

	// ===== OwnerLattice signature verification =====

	@Test
	public void testOwnerLatticeVerifiesSignature() {
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpAttacker = AKeyPair.generate();

		AHashMap<Keyword, ACell> info = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://host:1")),
			TEST_TYPE, Strings.create("0.8.3"),
			null, 1000L
		);

		// Attacker signs info but places it under owner's key
		SignedData<ACell> forged = kpAttacker.signData((ACell) info);
		@SuppressWarnings({"unchecked", "rawtypes"})
		AHashMap<ACell, SignedData<ACell>> forgedMap =
			(AHashMap) Maps.of(kpOwner.getAccountKey(), forged);

		LatticeContext ctx = LatticeContext.create(null, kpOwner);
		AHashMap<ACell, SignedData<ACell>> result =
			P2PLattice.NODES_LATTICE.merge(ctx, Maps.empty(), forgedMap);

		assertNull(result.get(kpOwner.getAccountKey()), "Forged entry should be rejected");
	}

	@Test
	public void testOwnerLatticeAcceptsValidSignature() {
		AKeyPair kp = AKeyPair.generate();

		AHashMap<Keyword, ACell> info = P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://host:1")),
			TEST_TYPE, Strings.create("0.8.3"),
			null, 1000L
		);

		AHashMap<ACell, SignedData<ACell>> entry = P2PLattice.createSignedEntry(kp, info);

		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<ACell>> result =
			P2PLattice.NODES_LATTICE.merge(ctx, Maps.empty(), entry);

		assertNotNull(result.get(kp.getAccountKey()));
		assertEquals(info, result.get(kp.getAccountKey()).getValue());
	}

	// ===== ROOT path navigation =====

	@Test
	public void testLatticeRootP2PPath() {
		// :p2p should resolve to a KeyedLattice
		ALattice<?> p2pLattice = Lattice.ROOT.path(Keywords.P2P);
		assertNotNull(p2pLattice, ":p2p should be registered in ROOT");
		assertInstanceOf(KeyedLattice.class, p2pLattice);

		// :p2p / :nodes should resolve to OwnerLattice
		ALattice<?> nodesLattice = p2pLattice.path(Keywords.NODES);
		assertNotNull(nodesLattice, ":nodes should be in :p2p");
		assertInstanceOf(OwnerLattice.class, nodesLattice);
	}

	@Test
	public void testLatticeRootP2PUnknownKeyReturnsNull() {
		ALattice<?> p2pLattice = Lattice.ROOT.path(Keywords.P2P);
		assertNull(p2pLattice.path(Keyword.intern("nonexistent")));
	}

	// ===== Multi-node convergence =====

	@Test
	public void testMultipleNodesConverge() {
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();
		AKeyPair kpC = AKeyPair.generate();

		AHashMap<ACell, SignedData<ACell>> entryA = P2PLattice.createSignedEntry(kpA,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://nodeA:18888")),
				TEST_TYPE, Strings.create("0.8.3"), null, 1000L));

		AHashMap<ACell, SignedData<ACell>> entryB = P2PLattice.createSignedEntry(kpB,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://nodeB:18888")),
				TEST_TYPE, Strings.create("0.8.3"), null, 1000L));

		AHashMap<ACell, SignedData<ACell>> entryC = P2PLattice.createSignedEntry(kpC,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://nodeC:18888"), Strings.create("wss://nodeC:443")),
				TEST_TYPE, Strings.create("0.8.3"),
				Vectors.of(Strings.create("us-east")),
				1000L));

		// Merge all three
		AHashMap<ACell, SignedData<ACell>> merged = P2PLattice.NODES_LATTICE.merge(entryA, entryB);
		merged = P2PLattice.NODES_LATTICE.merge(merged, entryC);

		assertEquals(3, merged.size());
		assertNotNull(P2PLattice.getNodeInfo(merged, kpA.getAccountKey()));
		assertNotNull(P2PLattice.getNodeInfo(merged, kpB.getAccountKey()));

		// Verify node C has regions and multiple transports
		AHashMap<Keyword, ACell> infoC = P2PLattice.getNodeInfo(merged, kpC.getAccountKey());
		assertNotNull(infoC);
		assertEquals(2, ((AVector<?>) infoC.get(Keywords.TRANSPORTS)).count());
		assertEquals(Strings.create("us-east"),
			((AVector<?>) infoC.get(Keywords.REGIONS)).get(0));
	}

	@Test
	public void testNodeUpdateOverwritesOldEntry() {
		AKeyPair kp = AKeyPair.generate();
		AccountKey key = kp.getAccountKey();

		AHashMap<ACell, SignedData<ACell>> entry1 = P2PLattice.createSignedEntry(kp,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://old:18888")),
				TEST_TYPE, Strings.create("0.8.2"), null, 1000L));

		AHashMap<ACell, SignedData<ACell>> entry2 = P2PLattice.createSignedEntry(kp,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://new:18888")),
				TEST_TYPE, Strings.create("0.8.3"), null, 2000L));

		// Merge — newer entry should win via LWW
		AHashMap<ACell, SignedData<ACell>> merged = P2PLattice.NODES_LATTICE.merge(entry1, entry2);

		AHashMap<Keyword, ACell> info = P2PLattice.getNodeInfo(merged, key);
		assertEquals(Strings.create("0.8.3"), info.get(Keywords.VERSION));
		assertEquals(Strings.create("tcp://new:18888"),
			((AVector<?>) info.get(Keywords.TRANSPORTS)).get(0));
	}

	// ===== Generic lattice properties =====

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void testGenericLatticeProperties() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();

		AHashMap<ACell, SignedData<ACell>> v1 = P2PLattice.createSignedEntry(kp1,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://a:1")),
				TEST_TYPE, Strings.create("v1"), null, 1000L));

		AHashMap<ACell, SignedData<ACell>> v2 = P2PLattice.createSignedEntry(kp2,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://b:2")),
				TEST_TYPE, Strings.create("v2"), null, 2000L));

		LatticeTest.doLatticeTest((ALattice) P2PLattice.NODES_LATTICE, v1, v2);
	}

	// ===== Null / empty edge cases =====

	@Test
	public void testGetNodeInfoNullSafe() {
		AKeyPair kp = AKeyPair.generate();
		assertNull(P2PLattice.getNodeInfo(null, kp.getAccountKey()));
	}

	@Test
	public void testGetSignedEntryNullSafe() {
		AKeyPair kp = AKeyPair.generate();
		assertNull(P2PLattice.getSignedEntry(null, kp.getAccountKey()));
	}

	@Test
	public void testGetNodeInfoMissingKey() {
		AKeyPair kpPresent = AKeyPair.generate();
		AKeyPair kpAbsent = AKeyPair.generate();

		AHashMap<ACell, SignedData<ACell>> entry = P2PLattice.createSignedEntry(kpPresent,
			P2PLattice.createNodeInfo(
				Vectors.of(Strings.create("tcp://host:1")),
				TEST_TYPE, Strings.create("v1"), null, 1000L));

		assertNull(P2PLattice.getNodeInfo(entry, kpAbsent.getAccountKey()));
	}
}
