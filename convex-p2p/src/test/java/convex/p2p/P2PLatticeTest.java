package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.ReservedLattice;
import convex.social.Social;

/**
 * Tests for the P2P root lattice structure.
 */
public class P2PLatticeTest {

	private static final AKeyPair KP = AKeyPair.generate();
	private static final AccountKey KEY = KP.getAccountKey();

	/**
	 * {@code ACursor.get(ACell...)} is declared to return the cursor's own value type,
	 * so a value read from a descended path needs re-typing at the call site.
	 */
	@SuppressWarnings("unchecked")
	private static AHashMap<ACell, SignedData<ACell>> ownerMap(ACell value) {
		return (AHashMap<ACell, SignedData<ACell>>) value;
	}

	// ===== Structure =====

	@Test
	public void testTopLevelRegions() {
		assertNotNull(P2PLattice.ROOT.path(P2PLattice.KEY_P2P));
		assertNotNull(P2PLattice.ROOT.path(P2PLattice.KEY_ID));
		assertNotNull(P2PLattice.ROOT.path(P2PLattice.KEY_KAD));

		// Application regions of Lattice.ROOT are deliberately absent
		assertNull(P2PLattice.ROOT.path(Keywords.DATA));
		assertNull(P2PLattice.ROOT.path(Keywords.FS));
		assertNull(P2PLattice.ROOT.path(Keywords.KV));
		assertNull(P2PLattice.ROOT.path(Keywords.QUEUE));

		assertNull(P2PLattice.ROOT.path(Keyword.intern("nonexistent")));
	}

	/**
	 * Paths are wire-visible: LATTICE_VALUE merges at the literal path it carries, so
	 * a P2P node and a full node on Lattice.ROOT must address the registry identically.
	 */
	@Test
	public void testNodeRegistryPathMatchesCoreRoot() {
		assertNotNull(P2PLattice.ROOT.path(Keywords.P2P, Keywords.NODES));
		assertNotNull(Lattice.ROOT.path(Keywords.P2P, Keywords.NODES));

		// The same lattice instances, so merge semantics cannot drift
		assertSame(Lattice.ROOT.path(Keywords.P2P), P2PLattice.ROOT.path(Keywords.P2P));
		assertSame(Lattice.ROOT.path(Keywords.P2P, Keywords.NODES),
			P2PLattice.ROOT.path(Keywords.P2P, Keywords.NODES));
	}

	// ===== :id region =====

	@Test
	public void testIdentityMergeAndRead() {
		AHashMap<Keyword, ACell> identity = P2PLattice.createIdentity(
			Strings.create("alice"), Vectors.of(KEY), null, 1000L);

		RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(P2PLattice.ROOT);
		cursor.path(P2PLattice.KEY_ID).merge(P2PLattice.createSignedIdentity(KP, identity));

		AHashMap<ACell, SignedData<ACell>> merged = ownerMap(cursor.get(P2PLattice.KEY_ID));

		AHashMap<Keyword, ACell> read = P2PLattice.getIdentity(merged, KEY);
		assertNotNull(read);
		assertEquals(Strings.create("alice"), read.get(P2PLattice.ID_NAME));

		AVector<ACell> nodes = P2PLattice.getIdentityNodes(merged, KEY);
		assertEquals(1, nodes.count());
		assertEquals(KEY, nodes.get(0));
	}

	@Test
	public void testIdentityLWWByTimestamp() {
		RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(P2PLattice.ROOT);

		cursor.path(P2PLattice.KEY_ID).merge(P2PLattice.createSignedIdentity(KP,
			P2PLattice.createIdentity(Strings.create("old"), null, null, 1000L)));

		// Newer timestamp wins
		cursor.path(P2PLattice.KEY_ID).merge(P2PLattice.createSignedIdentity(KP,
			P2PLattice.createIdentity(Strings.create("new"), null, null, 2000L)));

		// Older timestamp is ignored
		cursor.path(P2PLattice.KEY_ID).merge(P2PLattice.createSignedIdentity(KP,
			P2PLattice.createIdentity(Strings.create("stale"), null, null, 500L)));

		AHashMap<ACell, SignedData<ACell>> merged = ownerMap(cursor.get(P2PLattice.KEY_ID));
		assertEquals(Strings.create("new"),
			P2PLattice.getIdentity(merged, KEY).get(P2PLattice.ID_NAME));
	}

	// ===== Reserved :kad region =====

	/**
	 * Our wiring only: {@code :kad} is declared in the root and backed by a
	 * {@link ReservedLattice}. Its behaviour — discarding values, not being navigable,
	 * and not aborting sibling merges — is core's
	 * ({@code convex.lattice.generic.ReservedLatticeTest}).
	 */
	@Test
	public void testKadIsAReservedRegion() {
		ALattice<ACell> kad = P2PLattice.ROOT.path(P2PLattice.KEY_KAD);
		assertNotNull(kad, "Reserved region must still be declared in the root");
		assertInstanceOf(ReservedLattice.class, kad);
	}

	// ===== Cross-root interoperability =====

	/**
	 * Our specific claim: this module's extra regions interoperate with a node running
	 * {@code Lattice.ROOT} — it keeps {@code :p2p} and ignores {@code :id} and
	 * {@code :kad}. The underlying mechanism (unregistered keys are dropped on merge) is
	 * a KeyedLattice property, covered by
	 * {@code GenericLatticeTest.testKeyedLatticeMergeIgnoresUnregisteredKeys}.
	 */
	@Test
	public void testCoreRootIgnoresUnknownTopLevelRegions() {
		AHashMap<Keyword, ACell> nodeInfo = convex.lattice.P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://example.com:18888")),
			Strings.create("Convex Lattice Node"), Strings.create("test"), null, 1000L);

		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> incoming = (Index<Keyword, ACell>) Index.EMPTY
			.assoc(P2PLattice.KEY_P2P, Index.EMPTY.assoc(P2PLattice.KEY_NODES,
				convex.lattice.P2PLattice.createSignedEntry(KP, nodeInfo)))
			.assoc(P2PLattice.KEY_ID, P2PLattice.createSignedIdentity(KP,
				P2PLattice.createIdentity(Strings.create("carol"), null, null, 1000L)))
			.assoc(P2PLattice.KEY_KAD, Vectors.of(1, 2, 3));

		Index<Keyword, ACell> merged = Lattice.ROOT.merge(
			LatticeContext.EMPTY, Lattice.ROOT.zero(), incoming);

		assertNotNull(merged.get(P2PLattice.KEY_P2P), ":p2p is understood by both roots");
		assertNull(merged.get(P2PLattice.KEY_ID), ":id is ignored, not fatal");
		assertNull(merged.get(P2PLattice.KEY_KAD), ":kad is ignored, not fatal");
	}

	// ===== Region sets =====

	/** The default node region set is the infrastructure floor plus the bundled apps. */
	@Test
	public void testNodeRootAddsApplicationRegions() {
		assertNotNull(P2PLattice.NODE_ROOT.path(Social.KEY_SOCIAL));

		// ...without disturbing the P2P regions
		assertSame(P2PLattice.ROOT.path(Keywords.P2P, Keywords.NODES),
			P2PLattice.NODE_ROOT.path(Keywords.P2P, Keywords.NODES));
		assertSame(P2PLattice.ROOT.path(P2PLattice.KEY_ID),
			P2PLattice.NODE_ROOT.path(P2PLattice.KEY_ID));
	}

	/** Switching applications off leaves the infrastructure regions intact. */
	@Test
	public void testRootIsInfrastructureOnly() {
		assertNull(P2PLattice.ROOT.path(Social.KEY_SOCIAL));

		assertNotNull(P2PLattice.ROOT.path(Keywords.P2P, Keywords.NODES));
		assertNotNull(P2PLattice.ROOT.path(P2PLattice.KEY_ID));
		assertNotNull(P2PLattice.ROOT.path(P2PLattice.KEY_KAD));
	}

	/**
	 * The property that makes switching a region off a safe local decision: a node
	 * serving only ROOT drops :social from an incoming value and merges the rest, rather
	 * than rejecting the whole update.
	 */
	@Test
	public void testSocialOffNodeStillMergesP2PRegions() {
		AHashMap<Keyword, ACell> nodeInfo = convex.lattice.P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://example.com:18888")),
			Strings.create("Convex Lattice Node"), Strings.create("test"), null, 1000L);

		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> incoming = (Index<Keyword, ACell>) Index.EMPTY
			.assoc(P2PLattice.KEY_P2P, Index.EMPTY.assoc(P2PLattice.KEY_NODES,
				convex.lattice.P2PLattice.createSignedEntry(KP, nodeInfo)))
			.assoc(Social.KEY_SOCIAL, Maps.empty());

		Index<Keyword, ACell> merged = P2PLattice.ROOT.merge(
			LatticeContext.EMPTY, P2PLattice.ROOT.zero(), incoming);

		assertNotNull(merged.get(P2PLattice.KEY_P2P), "Discovery data still merges");
		assertNull(merged.get(Social.KEY_SOCIAL), ":social ignored, not fatal");
	}

	/**
	 * Region sets are not a closed set either: an application not bundled here composes
	 * the same way NODE_ROOT composes social.
	 */
	@Test
	public void testApplicationRegionCanBeComposedOnto() {
		Keyword appKey = Keyword.intern("someapp");
		KeyedLattice composed = P2PLattice.ROOT.addLattice(appKey, MaxLattice.create());

		// P2P regions still resolve at their original paths
		assertSame(P2PLattice.ROOT.path(Keywords.P2P, Keywords.NODES),
			composed.path(Keywords.P2P, Keywords.NODES));
		assertNotNull(composed.path(P2PLattice.KEY_ID));
		assertNotNull(composed.path(appKey));

		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> incoming = (Index<Keyword, ACell>) Index.EMPTY
			.assoc(P2PLattice.KEY_ID, P2PLattice.createSignedIdentity(KP,
				P2PLattice.createIdentity(Strings.create("erin"), null, null, 1000L)))
			.assoc(appKey, CVMLong.create(42));

		Index<Keyword, ACell> merged = composed.merge(
			LatticeContext.EMPTY, composed.zero(), incoming);

		assertEquals(CVMLong.create(42), merged.get(appKey), "App region merges");
		assertEquals(Strings.create("erin"),
			P2PLattice.getIdentity(ownerMap(merged.get(P2PLattice.KEY_ID)), KEY)
				.get(P2PLattice.ID_NAME),
			"P2P regions unaffected");
	}

	/** The converse: a P2P node serves its own regions and ignores application ones. */
	@Test
	public void testP2PRootIgnoresApplicationRegions() {
		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> incoming = (Index<Keyword, ACell>) Index.EMPTY
			.assoc(Keywords.DATA, Index.EMPTY)
			.assoc(P2PLattice.KEY_ID, P2PLattice.createSignedIdentity(KP,
				P2PLattice.createIdentity(Strings.create("dave"), null, null, 1000L)));

		Index<Keyword, ACell> merged = P2PLattice.ROOT.merge(
			LatticeContext.EMPTY, P2PLattice.ROOT.zero(), incoming);

		assertNotNull(merged.get(P2PLattice.KEY_ID));
		assertNull(merged.get(Keywords.DATA), ":data is ignored by a P2P node");
	}
}
