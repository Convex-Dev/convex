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
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.LatticeContext;
import convex.node.NodeConfig;
import convex.node.NodeServer;
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

	/** The base transport must not discover P2P behaviour from the root's shape. */
	@Test
	public void testGenericNodeServerDoesNotPublishNodeInfo() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		NodeConfig config=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://localhost:18888"),
			NodeConfig.PORT,CVMLong.create(-1)));
		try (NodeServer<Index<Keyword,ACell>> raw=
				new NodeServer<>(P2PLattice.ROOT,store,config)) {
			raw.setMergeContext(LatticeContext.create(null,keyPair));
			raw.launch();
			ACell nodes=raw.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
			assertTrue(nodes==null || (nodes instanceof AHashMap<?,?> map && map.isEmpty()),
				"schema-independent NodeServer must not publish P2P application records");
		}
	}

	@Test
	public void testPublishesNodeInfoWithConfiguredTransport() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		NodeConfig config=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://peer.example.com:18888"),
			NodeConfig.PORT,CVMLong.create(-1)));
		node=P2PNode.create(store,config,keyPair);

		node.launch();

		AHashMap<Keyword,ACell> info=nodeInfo(keyPair.getAccountKey());
		assertNotNull(info);
		assertEquals(Strings.create("tcp://peer.example.com:18888"),
			((AVector<?>)info.get(Keywords.TRANSPORTS)).get(0));
		assertEquals(Strings.create("Convex Lattice Node"),info.get(Keywords.TYPE));
		assertNotNull(info.get(Keywords.VERSION));
		assertNotNull(info.get(Keywords.TIMESTAMP));
	}

	@Test
	public void testLocalNetworkNodeInfoUsesBoundPort() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		node=P2PNode.create(store,NodeConfig.localNetwork(),keyPair);

		node.launch();

		assertNotNull(node.getPort());
		assertTrue(node.getPort()>0);
		AHashMap<Keyword,ACell> info=nodeInfo(keyPair.getAccountKey());
		assertEquals(Strings.create("tcp://localhost:"+node.getPort()),
			((AVector<?>)info.get(Keywords.TRANSPORTS)).get(0));
	}

	@Test
	public void testOutboundOnlyNodeInfoHasEmptyTransports() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		node=P2PNode.create(store,NodeConfig.port(-1),keyPair);

		node.launch();

		assertEquals(Vectors.empty(),
			nodeInfo(keyPair.getAccountKey()).get(Keywords.TRANSPORTS));
	}

	@Test
	public void testKeylessNodeDoesNotPublishNodeInfo() throws Exception {
		node=P2PNode.create(store,NodeConfig.port(-1),null);

		node.launch();

		ACell nodes=node.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		assertTrue(nodes==null || (nodes instanceof AHashMap<?,?> map && map.isEmpty()));
	}

	@Test
	public void testP2PLaunchValidatesAdvertisedURL() throws Exception {
		NodeConfig rejected=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://localhost:18888"),
			NodeConfig.PORT,CVMLong.create(-1)));
		node=P2PNode.create(store,rejected,AKeyPair.generate());
		assertThrows(IllegalStateException.class,node::launch);
		node.close();

		NodeConfig allowed=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://localhost:18888"),
			NodeConfig.ALLOW_PRIVATE_URL,CVMBool.TRUE,
			NodeConfig.PORT,CVMLong.create(-1)));
		node=P2PNode.create(store,allowed,AKeyPair.generate());
		node.launch();
		assertNotNull(nodeInfo(node.p2p().getUserKey()));
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

	@SuppressWarnings("unchecked")
	private AHashMap<Keyword,ACell> nodeInfo(AccountKey key) {
		AHashMap<ACell,SignedData<ACell>> nodes=(AHashMap<ACell,SignedData<ACell>>)
			(ACell)node.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		return convex.lattice.P2PLattice.getNodeInfo(nodes,key);
	}
}
