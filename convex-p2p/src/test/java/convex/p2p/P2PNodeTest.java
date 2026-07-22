package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.etch.EtchStore;
import convex.node.NodeConfig;

/**
 * Tests for the P2PNode stub.
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
}
