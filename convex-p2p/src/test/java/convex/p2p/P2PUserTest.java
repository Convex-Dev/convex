package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.etch.EtchStore;
import convex.lattice.cursor.ALatticeCursor;
import convex.node.NodeConfig;

/**
 * Tests for the {@code node.p2p(userID).cursor()} application API — reading and writing
 * a user's owned P2P area through a cursor, with signing handled by the cursor chain.
 */
public class P2PUserTest {

	private static final AKeyPair KP = AKeyPair.generate();
	private static final AccountKey KEY = KP.getAccountKey();

	private EtchStore store;
	private P2PNode node;

	@BeforeEach
	public void setUp() throws IOException {
		store = EtchStore.createTemp("p2p-user-test");
		// Negative port = local-only; these tests exercise lattice behaviour, not networking
		node = P2PNode.create(store, NodeConfig.port(-1), KP);
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (node != null) node.close();
		if (store != null) store.close();
	}

	/** Reads the raw signed slot straight from the root, bypassing the user view. */
	@SuppressWarnings("unchecked")
	private SignedData<ACell> rawIdentitySlot(AccountKey userKey) {
		AHashMap<ACell, SignedData<ACell>> region =
			(AHashMap<ACell, SignedData<ACell>>) (ACell) node.getCursor().get(P2PLattice.KEY_ID);
		return (region == null) ? null : region.get(userKey);
	}

	// ===== The headline API =====

	/**
	 * The shape the application is meant to use: get a cursor, write a value, sync.
	 * No SignedData, no key handling, no lattice plumbing at the call site.
	 */
	@Test
	public void testCursorWriteAndSync() {
		P2PUser me = node.p2p(KEY);

		me.cursor().set(P2PLattice.createIdentity(Strings.create("alice"), null, null, 1000L));
		me.sync();

		AHashMap<Keyword, ACell> identity = me.getIdentity();
		assertNotNull(identity);
		assertEquals(Strings.create("alice"), identity.get(P2PLattice.ID_NAME));
	}

	/** The write must land in the shared lattice, not just in the user view. */
	@Test
	public void testWriteReachesLatticeRoot() {
		P2PUser me = node.p2p(KEY);
		me.cursor().set(P2PLattice.createIdentity(Strings.create("alice"), null, null, 1000L));
		me.sync();

		AHashMap<ACell, SignedData<ACell>> region = ownerMap(node.getCursor().get(P2PLattice.KEY_ID));
		assertEquals(Strings.create("alice"),
			P2PLattice.getIdentity(region, KEY).get(P2PLattice.ID_NAME));
	}

	@Test
	public void testUserPersistenceDelegatesThroughApplication() throws Exception {
		P2PUser me=node.p2p(KEY);
		me.setIdentity(Strings.create("persisted"),null,1000L);

		ACell persisted=me.persist();

		assertEquals(me.cursor().get(),persisted);
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<ACell, SignedData<ACell>> ownerMap(ACell value) {
		return (AHashMap<ACell, SignedData<ACell>>) value;
	}

	/** node.p2p() targets the node's own key, so it is the writable view. */
	@Test
	public void testOwnUserShorthand() {
		assertEquals(KEY, node.p2p().getUserKey());

		node.p2p().cursor().set(P2PLattice.createIdentity(Strings.create("self"), null, null, 1000L));
		node.p2p().sync();

		assertEquals(Strings.create("self"),
			node.p2p().getIdentity().get(P2PLattice.ID_NAME));
	}

	// ===== Signing is handled by the cursor chain =====

	/**
	 * The application never sees SignedData, but the stored slot must be signed by the
	 * user's key — that is the whole point of the signing boundary.
	 */
	@Test
	public void testValueIsStoredSignedByUser() {
		P2PUser me = node.p2p(KEY);
		me.cursor().set(P2PLattice.createIdentity(Strings.create("alice"), null, null, 1000L));
		me.sync();

		SignedData<ACell> slot = rawIdentitySlot(KEY);
		assertNotNull(slot, "Identity must be stored as a signed slot");
		assertEquals(KEY, slot.getAccountKey(), "Slot must be signed by the owning user");
		assertTrue(slot.checkSignature(), "Stored signature must verify");

		// The cursor presents the unsigned value, so the app never unwraps it
		assertEquals(slot.getValue(), me.cursor().get());
	}

	@Test
	public void testOwnUserShorthandRequiresKeyPair() throws IOException {
		try (EtchStore keylessStore = EtchStore.createTemp("p2p-user-keyless2");
				P2PNode keyless = P2PNode.create(keylessStore, NodeConfig.port(-1), null)) {
			assertThrows(IllegalStateException.class, keyless::p2p);
		}
	}

	// ===== Other users' areas =====

	/** Another user's published area is readable through the same API. */
	@Test
	public void testOtherUserAreaIsReadable() {
		AKeyPair otherKP = AKeyPair.generate();
		AccountKey otherKey = otherKP.getAccountKey();

		// Arrive as a merge, the way a value would over the network
		node.getCursor().path(P2PLattice.KEY_ID).merge(
			P2PLattice.createSignedIdentity(otherKP,
				P2PLattice.createIdentity(Strings.create("bob"), null, null, 1000L)));

		P2PUser them = node.p2p(otherKey);
		assertEquals(otherKey, them.getUserKey());
		assertEquals(Strings.create("bob"), them.getIdentity().get(P2PLattice.ID_NAME));
	}

	/** An owner path requires its corresponding key from the context signing policy. */
	@Test
	public void testForeignAreaWriteRequiresSigner() {
		AccountKey otherKey = AKeyPair.generate().getAccountKey();

		P2PUser them = node.p2p(otherKey);
		assertThrows(IllegalStateException.class,()->them.cursor().set(
			P2PLattice.createIdentity(Strings.create("mallory"),null,null,9000L)));

		SignedData<ACell> slot = rawIdentitySlot(otherKey);
		assertNull(slot,"No value is written without the requested owner's signer");
	}

	// ===== Node record area =====

	/** The node record lives in the separate :p2p region but works the same way. */
	@Test
	public void testNodeCursorWriteAndRead() {
		P2PUser me = node.p2p(KEY);

		AHashMap<Keyword, ACell> info = convex.lattice.P2PLattice.createNodeInfo(
			Vectors.of(Strings.create("tcp://example.com:18888")),
			Strings.create("Convex Lattice Node"), Strings.create("test"), null, 1000L);
		me.nodeCursor().set(info);
		me.nodeCursor().sync();

		AHashMap<ACell, SignedData<ACell>> nodes = ownerMap(
			node.getCursor().get(P2PLattice.KEY_P2P, P2PLattice.KEY_NODES));
		AHashMap<Keyword, ACell> read = convex.lattice.P2PLattice.getNodeInfo(nodes, KEY);
		assertNotNull(read);
		assertEquals(Strings.create("Convex Lattice Node"), read.get(Keywords.TYPE));
	}

	/** Identity and node record are independent regions — writing one leaves the other alone. */
	@Test
	public void testIdentityAndNodeRecordAreIndependent() {
		P2PUser me = node.p2p(KEY);

		me.cursor().set(P2PLattice.createIdentity(Strings.create("alice"), null, null, 1000L));
		me.sync();

		assertNotNull(me.getIdentity());
		assertNull(me.nodeCursor().get(), "Publishing identity must not create a node record");
	}

	// ===== Fork / sync-on-demand =====

	/**
	 * Batch edits on a fork stay invisible until sync — the "sync on demand" model.
	 */
	@Test
	public void testForkIsolatesUntilSync() {
		P2PUser me = node.p2p(KEY);
		me.cursor().set(P2PLattice.createIdentity(Strings.create("original"), null, null, 1000L));
		me.sync();

		P2PUser draft = me.fork();
		draft.cursor().set(P2PLattice.createIdentity(Strings.create("draft"), null, null, 2000L));

		// Parent unchanged before sync
		assertEquals(Strings.create("original"),
			node.p2p(KEY).getIdentity().get(P2PLattice.ID_NAME));

		draft.sync();

		assertEquals(Strings.create("draft"),
			node.p2p(KEY).getIdentity().get(P2PLattice.ID_NAME));
	}

	/**
	 * A stale forked edit loses to a newer parent value. This is the :id region's LWW
	 * wiring showing through P2PUser.fork() — the fork/sync mechanism itself is core's
	 * ({@code SignedCursorTest.testForkSyncAboveSigningBoundary}).
	 */
	@Test
	public void testForkSyncMergesByTimestamp() {
		P2PUser me = node.p2p(KEY);
		me.cursor().set(P2PLattice.createIdentity(Strings.create("base"), null, null, 1000L));
		me.sync();

		P2PUser draft = me.fork();
		draft.cursor().set(P2PLattice.createIdentity(Strings.create("stale"), null, null, 500L));

		// Parent moves on while the fork is open
		P2PUser me2 = node.p2p(KEY);
		me2.cursor().set(P2PLattice.createIdentity(Strings.create("newer"), null, null, 3000L));
		me2.sync();

		draft.sync();

		assertEquals(Strings.create("newer"),
			node.p2p(KEY).getIdentity().get(P2PLattice.ID_NAME),
			"Older forked edit must not overwrite a newer parent value");
	}

	// ===== Scoping =====

	/**
	 * The cursor is rooted at this user's own area: its value is that user's identity
	 * map, with no other user's data reachable through it.
	 */
	@Test
	public void testCursorIsScopedToOneUser() {
		AKeyPair otherKP = AKeyPair.generate();
		AccountKey otherKey = otherKP.getAccountKey();

		node.p2p(KEY).cursor().set(
			P2PLattice.createIdentity(Strings.create("mine"), null, null, 1000L));
		node.p2p(KEY).sync();

		// Another user's value arrives by merge, as it would over the network
		node.getCursor().path(P2PLattice.KEY_ID).merge(
			P2PLattice.createSignedIdentity(otherKP,
				P2PLattice.createIdentity(Strings.create("theirs"), null, null, 1000L)));

		// Both are present at the region level...
		AHashMap<ACell, SignedData<ACell>> region = ownerMap(node.getCursor().get(P2PLattice.KEY_ID));
		assertEquals(2, region.count());

		// ...but each user's cursor sees only its own
		ALatticeCursor<ACell> mine = node.p2p(KEY).cursor();
		assertEquals(Strings.create("mine"),
			((AHashMap<Keyword, ACell>) mine.get()).get(P2PLattice.ID_NAME));
		assertNull(mine.get(otherKey), "Another user must not be reachable from this cursor");

		assertEquals(Strings.create("theirs"),
			node.p2p(otherKey).getIdentity().get(P2PLattice.ID_NAME));
	}

	// ===== Cursor identity =====

	/** Each call builds a fresh view over the same underlying lattice state. */
	@Test
	public void testViewsShareUnderlyingState() {
		node.p2p(KEY).cursor().set(
			P2PLattice.createIdentity(Strings.create("shared"), null, null, 1000L));
		node.p2p(KEY).sync();

		ALatticeCursor<ACell> a = node.p2p(KEY).cursor();
		ALatticeCursor<ACell> b = node.p2p(KEY).cursor();
		assertEquals(a.get(), b.get());
	}
}
