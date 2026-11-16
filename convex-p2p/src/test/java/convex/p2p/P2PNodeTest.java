package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
import convex.core.store.MemoryStore;
import convex.lattice.LatticeContext;
import convex.net.PeerName;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import convex.node.RelayHost;
import convex.social.Social;

/**
 * Tests for P2PNode lifecycle and region composition.
 */
public class P2PNodeTest {

	private MemoryStore store;
	private P2PNode node;
	private final ConcurrentLinkedQueue<Throwable> unexpectedFailures=new ConcurrentLinkedQueue<>();

	private P2PNode observe(P2PNode created) {
		return created.onInitialisationFailure((peer,failure) -> unexpectedFailures.add(failure));
	}

	@BeforeEach
	public void setUp() {
		// No test reopens the store, so it need not be on disk
		store = new MemoryStore();
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (node != null) node.close();
		if (store != null) store.close();
		assertEquals(List.of(),new ArrayList<>(unexpectedFailures),"Unexpected peer initialisation failure");
	}

	@Test
	public void testCreate() {
		node = observe(P2PNode.create(store, NodeConfig.create(), AKeyPair.generate()));

		assertNotNull(node.getNodeServer());
		assertNotNull(node.getCursor());
		assertFalse(node.isRunning());
	}

	/** The host hands incoming values to the P2P application, which merges a path delivery at its level. */
	@Test
	public void testIncomingRegistryValueReachesApplication() {
		AKeyPair keyPair=AKeyPair.generate();
		AKeyPair remote=AKeyPair.generate();
		node=observe(P2PNode.create(store,NodeConfig.create(),keyPair));
		AHashMap<Keyword,ACell> info=P2PLattice.createNodeInfo(
			Vectors.empty(),Strings.create("test"),Strings.create("0"),Vectors.empty(),false,
			System.currentTimeMillis());
		node.getNodeServer().mergeAt(P2PLattice.createSignedEntry(remote,info),
			P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		assertEquals(info,node.getApplication().node(remote.getAccountKey()).getNodeInfo());
		assertEquals(info,nodeInfo(remote.getAccountKey()));
	}

	@Test
	public void testCreateRequiresStore() {
		assertThrows(IllegalArgumentException.class,
			() -> P2PNode.create(null, NodeConfig.create(), null));
	}

	@Test
	public void testLaunchLocalOnly() throws IOException, InterruptedException {
		// Negative port = local-only mode, so no listener is opened
		node = observe(P2PNode.create(store, NodeConfig.port(-1), AKeyPair.generate()));
		node.launch();

		assertTrue(node.isRunning());
	}

	/**
	 * Seeding needs a running node, configuration precedes launch, a node launches
	 * once, and seeding starts once.
	 */
	@Test
	public void testCallsOutOfOrderFail() throws Exception {
		node=observe(P2PNode.create(store,NodeConfig.port(-1),AKeyPair.generate()));
		assertTrue(node.seed(List.of()).isCompletedExceptionally(),"seeding needs a running node");
		node.launch();
		assertThrows(IllegalStateException.class,node::relayMessages);
		assertThrows(IllegalStateException.class,() -> node.onInitialisationFailure((peer,failure) -> {}));
		assertThrows(IllegalStateException.class,() -> node.pointsOfPresence());
		assertThrows(IllegalStateException.class,node::launch,"a node launches once");
		assertTrue(node.isRunning());
		CompletableFuture<Void> seeding=node.seed(List.of(new PeerName(URI.create("tcp://localhost:1"),null)));
		assertSame(seeding,node.seed(List.of()),"seeding starts once");
	}

	/** The base transport must not discover P2P behaviour from the root's shape. */
	@Test
	public void testGenericNodeServerDoesNotPublishNodeInfo() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		NodeConfig config=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://localhost:18888"),
			NodeConfig.PORT,CVMLong.create(-1)));
		try (NodeServer<Index<Keyword,ACell>> raw=
				new NodeServer<>(RelayHost.open(P2PLattice.ROOT,store,
					LatticeContext.create(null,keyPair)),config)) {
			raw.launch();
			ACell nodes=raw.getCursor().getOrDefault(null,P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
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
		node=observe(P2PNode.create(store,config,keyPair));

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
		node=observe(P2PNode.create(store,NodeConfig.localNetwork(),keyPair));

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
		node=observe(P2PNode.create(store,NodeConfig.port(-1),keyPair));

		node.launch();

		assertEquals(Vectors.empty(),
			nodeInfo(keyPair.getAccountKey()).get(Keywords.TRANSPORTS));
	}

	@Test
	public void testKeylessNodeDoesNotPublishNodeInfo() throws Exception {
		node=observe(P2PNode.create(store,NodeConfig.port(-1),null));

		node.launch();

		ACell nodes=node.getCursor().getOrDefault(null,P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		assertTrue(nodes==null || (nodes instanceof AHashMap<?,?> map && map.isEmpty()));
	}

	@Test
	public void testP2PLaunchValidatesAdvertisedURL() throws Exception {
		NodeConfig rejected=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://localhost:18888"),
			NodeConfig.PORT,CVMLong.create(-1)));
		node=observe(P2PNode.create(store,rejected,AKeyPair.generate()));
		assertThrows(IllegalStateException.class,node::launch);
		node.close();

		NodeConfig allowed=NodeConfig.create(Maps.of(
			NodeConfig.URL,Strings.create("tcp://localhost:18888"),
			NodeConfig.ALLOW_PRIVATE_URL,CVMBool.TRUE,
			NodeConfig.PORT,CVMLong.create(-1)));
		node=observe(P2PNode.create(store,allowed,AKeyPair.generate()));
		node.launch();
		assertNotNull(nodeInfo(node.p2p().getUserKey()));
	}

	/**
	 * A peer's initialisation that fails at once, as a keyless node's always does, is
	 * forgotten, so the next connection to that peer runs it again rather than reporting
	 * the old failure.
	 */
	@Test
	@Timeout(30)
	public void testFailedPeerInitialisationRunsAgain() throws Exception {
		AKeyPair hubKey=AKeyPair.generate();
		try (MemoryStore hubStore=new MemoryStore();
				P2PNode hub=observe(P2PNode.create(hubStore,NodeConfig.localNetwork(),hubKey)).serveAllInbound()) {
			hub.launch();
			BlockingQueue<Throwable> reported=new LinkedBlockingQueue<>();
			node=P2PNode.create(store,NodeConfig.port(-1),null)
				.onInitialisationFailure((peer,failure) -> reported.add(failure));
			node.launch();
			Throwable first=connectFailure(hubKey.getAccountKey(),hub,reported);
			Throwable second=connectFailure(hubKey.getAccountKey(),hub,reported);
			assertNotSame(first,second);
			node.close();
			for (Throwable failure:reported) assertInstanceOf(IllegalStateException.class,failure);
		}
	}

	/**
	 * A node that knows no peer waits out a backoff before turning to its seeds; closing
	 * the node cancels that backoff, and with it the timer, so nothing it started outlives it.
	 */
	@Test
	public void testCloseCancelsTheSeedingBackoff() throws Exception {
		node=observe(P2PNode.create(store,NodeConfig.port(-1),AKeyPair.generate()));
		node.launch();
		node.seed(List.of());
		CompletableFuture<Void> backoff=node.seedBackoff();
		assertNotNull(backoff,"a node that knows no peer waits to seed as a last resort");
		assertFalse(backoff.isDone());
		node.close();
		assertTrue(backoff.isCancelled());
	}

	private Throwable connectFailure(AccountKey key,P2PNode hub,BlockingQueue<Throwable> reported) throws InterruptedException {
		Throwable failure=assertThrows(ExecutionException.class,
			() -> node.connect(key,hub.getHostAddress()).get(10,TimeUnit.SECONDS)).getCause();
		assertInstanceOf(IllegalStateException.class,failure);
		// Admission and the explicit connect can each initialise this keyless node.
		// Check every reported failure, including the exact one returned to the caller.
		Throwable observed;
		do {
			observed=reported.poll(10,TimeUnit.SECONDS);
			assertInstanceOf(IllegalStateException.class,observed);
		} while (observed!=failure);
		return failure;
	}

	/** The default node serves the application regions as well as the P2P ones. */
	@Test
	public void testDefaultNodeServesApplicationRegions() {
		node = observe(P2PNode.create(store, NodeConfig.port(-1), AKeyPair.generate()));

		var host=node.getNodeServer().getHost();
		var root=host.cursor();
		var application=node.getApplication().cursor();
		assertSame(root,application);
		assertNotNull(node.getApplication().getLattice());
		assertSame(root.get(),application.get());
		assertNotNull(node.getApplication().getLattice().path(Social.KEY_SOCIAL));
		assertNotNull(node.getApplication().getLattice().path(Keywords.P2P, Keywords.NODES));
	}

	/**
	 * Switching applications off is a per-node choice, not a different node server: the
	 * same class, launched with the infrastructure-only region set, and still a complete
	 * discovery node.
	 */
	@Test
	public void testSocialOffNodeIsStillAP2PNode() throws IOException, InterruptedException {
		node = observe(P2PNode.create(store, NodeConfig.port(-1), AKeyPair.generate(), P2PLattice.ROOT));
		node.launch();

		assertTrue(node.isRunning());
		assertNull(node.getApplication().getLattice().path(Social.KEY_SOCIAL));

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
			(ACell)node.getCursor().getOrDefault(null,P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		return P2PLattice.getNodeInfo(nodes,key);
	}
}
