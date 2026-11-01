package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.p2p.P2PLattice;

/**
 * Tests for P2PLattice — the {@code :p2p} region's merge rule.
 *
 * Verifies LWW merge on the record timestamp, owner signature verification, ROOT path
 * navigation, and multi-node convergence. The NodeInfo record format is tested in
 * {@code convex-p2p}, which defines it.
 */
public class P2PLatticeTest {

	/** A minimal timestamped record: the region merges any owner-signed map. */
	private static AHashMap<Keyword, ACell> record(String tag, long timestamp) {
		return Maps.of(Keywords.TIMESTAMP, CVMLong.create(timestamp), Keywords.TYPE, Strings.create(tag));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static AHashMap<ACell, SignedData<ACell>> entry(AKeyPair kp, AHashMap<Keyword, ACell> record) {
		return (AHashMap) Maps.of(kp.getAccountKey(), kp.signData((ACell) record));
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<Keyword, ACell> read(AHashMap<ACell, SignedData<ACell>> nodes, AccountKey key) {
		SignedData<ACell> signed = nodes.get(key);
		return (signed == null) ? null : (AHashMap<Keyword, ACell>) signed.getValue();
	}

	// ===== LWW merge =====

	@Test
	public void testLWWMergeHigherTimestampWins() {
		AHashMap<Keyword, ACell> old = record("old", 1000L);
		AHashMap<Keyword, ACell> newer = record("newer", 2000L);

		// LWW should pick the newer value
		assertEquals(newer, LWWLattice.INSTANCE.merge(old, newer));

		// Commutative
		assertEquals(newer, LWWLattice.INSTANCE.merge(newer, old));
	}

	@Test
	public void testLWWMergeEqualTimestamp() {
		AHashMap<Keyword, ACell> a = record("a", 1000L);
		AHashMap<Keyword, ACell> b = record("b", 1000L);

		// Equal timestamps — prefer own value
		assertSame(a, LWWLattice.INSTANCE.merge(a, b), "Should prefer own value");
		assertSame(b, LWWLattice.INSTANCE.merge(b, a), "Should prefer own value");
	}

	// ===== OwnerLattice signature verification =====

	@Test
	public void testOwnerLatticeVerifiesSignature() {
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpAttacker = AKeyPair.generate();

		// Attacker signs a record but places it under owner's key
		SignedData<ACell> forged = kpAttacker.signData((ACell) record("forged", 1000L));
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
		AHashMap<Keyword, ACell> record = record("valid", 1000L);

		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<ACell>> result =
			P2PLattice.NODES_LATTICE.merge(ctx, Maps.empty(), entry(kp, record));

		assertEquals(record, read(result, kp.getAccountKey()));
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
		assertInstanceOf(convex.lattice.generic.OwnerMapLattice.class, nodesLattice);
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

		AHashMap<ACell, SignedData<ACell>> merged = P2PLattice.NODES_LATTICE.merge(
			entry(kpA, record("A", 1000L)), entry(kpB, record("B", 1000L)));
		merged = P2PLattice.NODES_LATTICE.merge(merged, entry(kpC, record("C", 1000L)));

		assertEquals(3, merged.size());
		assertEquals(record("A", 1000L), read(merged, kpA.getAccountKey()));
		assertEquals(record("B", 1000L), read(merged, kpB.getAccountKey()));
		assertEquals(record("C", 1000L), read(merged, kpC.getAccountKey()));
	}

	@Test
	public void testNodeUpdateOverwritesOldEntry() {
		AKeyPair kp = AKeyPair.generate();

		// Merge — newer entry should win via LWW
		AHashMap<ACell, SignedData<ACell>> merged = P2PLattice.NODES_LATTICE.merge(
			entry(kp, record("old", 1000L)), entry(kp, record("new", 2000L)));

		assertEquals(record("new", 2000L), read(merged, kp.getAccountKey()));
	}

	// ===== Generic lattice properties =====

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void testGenericLatticeProperties() {
		AHashMap<ACell, SignedData<ACell>> v1 = entry(AKeyPair.generate(), record("v1", 1000L));
		AHashMap<ACell, SignedData<ACell>> v2 = entry(AKeyPair.generate(), record("v2", 2000L));

		LatticeTest.doLatticeTest((ALattice) P2PLattice.NODES_LATTICE, v1, v2);
	}
}
