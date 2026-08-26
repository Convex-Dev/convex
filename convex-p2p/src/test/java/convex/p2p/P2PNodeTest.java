package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Strings;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.social.Social;

/**
 * Tests for P2PNode lifecycle and region composition.
 */
public class P2PNodeTest {

	private EtchStore store;
	private P2PNode node;

	@BeforeEach
	public void setUp() throws IOException {
		store = EtchStore.createTemp("p2p-node-test");
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (node != null) node.close();
		if (store != null) store.close();
	}

	@Test
	public void testCreate() {
		node = P2PNode.create(store, NodeConfig.create(), AKeyPair.generate());

		assertNotNull(node.getNodeServer());
		assertNotNull(node.getCursor());
		assertFalse(node.isRunning());
	}

	@Test
	public void testCreateRequiresStore() {
		assertThrows(IllegalArgumentException.class,
			() -> P2PNode.create(null, NodeConfig.create(), null));
	}

	@Test
	public void testLaunchLocalOnly() throws IOException, InterruptedException {
		// Negative port = local-only mode, so no listener is opened
		node = P2PNode.create(store, NodeConfig.port(-1), AKeyPair.generate());
		node.launch();

		assertTrue(node.isRunning());
	}

	/** The default node serves the application regions as well as the P2P ones. */
	@Test
	public void testDefaultNodeServesApplicationRegions() {
		node = P2PNode.create(store, NodeConfig.port(-1), AKeyPair.generate());

		assertSame(node.getNodeServer().getRootComponent().cursor(),
			node.getApplication().cursor());
		assertNotNull(node.getNodeServer().getLattice().path(Social.KEY_SOCIAL));
		assertNotNull(node.getNodeServer().getLattice().path(Keywords.P2P, Keywords.NODES));
	}

	/**
	 * Switching applications off is a per-node choice, not a different node server: the
	 * same class, launched with the infrastructure-only region set, and still a complete
	 * discovery node.
	 */
	@Test
	public void testSocialOffNodeIsStillAP2PNode() throws IOException, InterruptedException {
		node = P2PNode.create(store, NodeConfig.port(-1), AKeyPair.generate(), P2PLattice.ROOT);
		node.launch();

		assertTrue(node.isRunning());
		assertNull(node.getNodeServer().getLattice().path(Social.KEY_SOCIAL));

		// Full P2P capability: identity and node registry both writable
		assertNotNull(node.p2p().cursor());
		node.p2p().cursor().set(
			P2PLattice.createIdentity(Strings.create("relay"), null, null, 1000L));
		node.p2p().sync();
		assertEquals(Strings.create("relay"),
			node.p2p().getIdentity().get(P2PLattice.ID_NAME));
	}
}
