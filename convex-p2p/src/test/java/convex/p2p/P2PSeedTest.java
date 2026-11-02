package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.auth.did.DID;
import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.etch.EtchStore;
import convex.net.PeerName;
import convex.net.PeerResolver;
import convex.net.Transports;
import convex.node.LatticeConnectionManager;
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;

/**
 * Joining a network through seed nodes, which are let go once other peers can
 * replace them, and rejoining after a restart from retained records alone.
 */
public class P2PSeedTest {

	private static final long TIMEOUT=10;

	private final AKeyPair hubKey=AKeyPair.generate();
	private final AKeyPair otherKey=AKeyPair.generate();
	private final AKeyPair joinerKey=AKeyPair.generate();
	private final List<AutoCloseable> resources=new ArrayList<>();

	@AfterEach
	public void tearDown() throws Exception {
		Collections.reverse(resources);
		for (AutoCloseable resource:resources) resource.close();
	}

	/** One ambient slot for the joiner; none for the others, so only the joiner dials. */
	private static LatticePropagatorConfig ambient(int slots) {
		return LatticePropagatorConfig.create(Maps.of(LatticePropagatorConfig.AMBIENT_PEERS,CVMLong.create(slots)));
	}

	private EtchStore store(String name) throws Exception {
		EtchStore store=EtchStore.createTemp(name);
		resources.add(store);
		return store;
	}

	private P2PNode launch(EtchStore store,NodeConfig config,int slots,AKeyPair key) throws Exception {
		P2PNode node=P2PNode.create(store,config,ambient(slots),key).serveAllInbound();
		resources.add(node);
		node.launch();
		return node;
	}

	private static AVector<ACell> seedList(AccountKey key,P2PNode node) {
		return Vectors.of(Maps.of(Strings.create("key"),Strings.create(key.toHexString()),
			Strings.create("url"),Strings.create("tcp://localhost:"+node.getPort())));
	}

	private static NodeConfig withSeeds(AVector<ACell> seeds) {
		return NodeConfig.create(NodeConfig.localNetwork().getMap().assoc(P2PNode.SEEDS,seeds));
	}

	/** A hub that holds a record of one other node, which it learned when that node connected. */
	private P2PNode hubKnowingOther() throws Exception {
		P2PNode hub=launch(store("seed-hub"),NodeConfig.localNetwork(),0,hubKey);
		P2PNode other=launch(store("seed-other"),NodeConfig.localNetwork(),0,otherKey);
		other.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS);
		return hub;
	}

	@Test
	public void testColdNodeJoinsThroughOneSeedAndReleasesIt() throws Exception {
		P2PNode hub=hubKnowingOther();
		NodeConfig config=withSeeds(seedList(hubKey.getAccountKey(),hub));
		P2PNode joiner=launch(store("seed-joiner"),config,1,joinerKey);

		joiner.seed(P2PNode.seeds(config)).get(TIMEOUT,TimeUnit.SECONDS);
		LatticeConnectionManager manager=joiner.propagationGroup().getConnectionManager();
		assertNotNull(manager.getConnection(otherKey.getAccountKey()),"a node learned from the seed fills the slot");
		assertNull(manager.getConnection(hubKey.getAccountKey()),"the route to the seed is closed");
		assertTrue(manager.getDesiredPeers().containsKey(hubKey.getAccountKey()),"the seed stays a candidate");
		assertNotNull(registry(hub).get(joinerKey.getAccountKey()),"the seed took the joiner's record");
	}

	@Test
	public void testSeedWithTheWrongKeyHoldsUpNoOther() throws Exception {
		P2PNode hub=hubKnowingOther();
		AccountKey wrongKey=AKeyPair.generate().getAccountKey();
		NodeConfig config=withSeeds(seedList(wrongKey,hub).concat(seedList(hubKey.getAccountKey(),hub)));
		P2PNode joiner=launch(store("seed-joiner"),config,1,joinerKey);

		joiner.seed(P2PNode.seeds(config)).get(TIMEOUT,TimeUnit.SECONDS);
		LatticeConnectionManager manager=joiner.propagationGroup().getConnectionManager();
		assertNotNull(manager.getConnection(otherKey.getAccountKey()));
		assertNull(manager.getConnection(wrongKey),"the endpoint never proved the configured key");
		assertFalse(manager.getDesiredPeers().containsKey(wrongKey),"a seed no record lists is forgotten");
	}

	/** A seed named by its address alone is admitted under the key it proves, and released like any other. */
	@Test
	public void testSeedNamedWithoutAKey() throws Exception {
		P2PNode hub=hubKnowingOther();
		NodeConfig config=withSeeds(Vectors.of(Maps.of(Strings.create("url"),address(hub))));
		P2PNode joiner=launch(store("seed-joiner"),config,1,joinerKey);

		joiner.seed(P2PNode.seeds(config)).get(TIMEOUT,TimeUnit.SECONDS);
		LatticeConnectionManager manager=joiner.propagationGroup().getConnectionManager();
		assertNotNull(manager.getConnection(otherKey.getAccountKey()));
		assertNull(manager.getConnection(hubKey.getAccountKey()),"the route to the seed is closed");
		assertTrue(manager.getDesiredPeers().containsKey(hubKey.getAccountKey()),"the seed stays a candidate");
	}

	/** A blocked seed, and this node named by its address alone, are skipped without holding up the others. */
	@Test
	public void testBlockedSeedAndOwnAddressAreSkipped() throws Exception {
		P2PNode hub=hubKnowingOther();
		P2PNode joiner=launch(store("seed-joiner"),NodeConfig.localNetwork(),1,joinerKey);
		AccountKey blocked=AKeyPair.generate().getAccountKey();
		joiner.block(blocked);

		joiner.seed(List.of(new PeerName(URI.create(address(hub).toString()),blocked),
				PeerResolver.parse(address(joiner).toString()),
				PeerResolver.parse(address(hub).toString())))
			.get(TIMEOUT,TimeUnit.SECONDS);
		LatticeConnectionManager manager=joiner.propagationGroup().getConnectionManager();
		assertNotNull(manager.getConnection(otherKey.getAccountKey()));
		assertFalse(manager.getDesiredPeers().containsKey(blocked));
		assertFalse(manager.getDesiredPeers().containsKey(joinerKey.getAccountKey()));
	}

	/** A did:key seed is located where this node knows its key, here at a contact address. */
	@Test
	public void testDIDKeySeedIsLocatedByWhatThisNodeKnows() throws Exception {
		P2PNode hub=launch(store("seed-hub"),NodeConfig.localNetwork(),0,hubKey);
		P2PNode joiner=launch(store("seed-joiner"),NodeConfig.localNetwork(),0,joinerKey);
		joiner.getApplication().contacts().contacted(hubKey.getAccountKey(),address(hub));

		joiner.seed(List.of(new PeerName(URI.create(DID.forKey(hubKey.getAccountKey()).toString()),null)));
		joiner.whenConnected(hubKey.getAccountKey()).get(TIMEOUT,TimeUnit.SECONDS);
	}

	/** A peer named to connect is kept by name and its record kept; a blocked one is refused before dialling. */
	@Test
	public void testConnectByName() throws Exception {
		P2PNode hub=launch(store("seed-hub"),NodeConfig.localNetwork(),0,hubKey);
		P2PNode leaf=launch(store("seed-joiner"),NodeConfig.localNetwork(),0,joinerKey);
		Convex peer=leaf.connect(PeerResolver.parse(address(hub).toString())).get(TIMEOUT,TimeUnit.SECONDS);
		assertEquals(hubKey.getAccountKey(),peer.getVerifiedPeer());
		assertTrue(leaf.isNamed(hubKey.getAccountKey()));

		P2PNode blocking=launch(store("seed-other"),NodeConfig.localNetwork(),0,otherKey);
		blocking.block(hubKey.getAccountKey());
		PeerName named=new PeerName(URI.create(address(hub).toString()),hubKey.getAccountKey());
		ExecutionException refused=assertThrows(ExecutionException.class,
			() -> blocking.connect(named).get(TIMEOUT,TimeUnit.SECONDS));
		assertTrue(refused.getCause() instanceof SecurityException,refused.toString());
	}

	@Test
	public void testNodeDoesNotSeedFromItself() throws Exception {
		P2PNode joiner=launch(store("seed-joiner"),NodeConfig.localNetwork(),1,joinerKey);
		CompletableFuture<Void> seeding=joiner.seed(List.of(new PeerName(URI.create(address(joiner).toString()),joinerKey.getAccountKey())));
		assertTrue(seeding.isDone(),"nothing left to seed from");
		assertTrue(joiner.propagationGroup().getConnectionManager().getDesiredPeers().isEmpty());
	}

	/** Losing every route is seeding from scratch, as when the node started. */
	@Test
	public void testNodeLeftWithoutRoutesSeedsAgain() throws Exception {
		P2PNode hub=launch(store("seed-hub"),NodeConfig.localNetwork(),0,hubKey);
		P2PNode other=launch(store("seed-other"),NodeConfig.localNetwork(),0,otherKey);
		other.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS);
		NodeConfig config=withSeeds(seedList(hubKey.getAccountKey(),hub));
		// Room for one candidate, which the other node takes, so the released seed is forgotten
		LatticePropagatorConfig group=LatticePropagatorConfig.create(Maps.of(
			LatticePropagatorConfig.AMBIENT_PEERS,CVMLong.ONE,
			LatticePropagatorConfig.MAX_DESIRED_PEERS,CVMLong.ONE));
		P2PNode joiner=P2PNode.create(store("seed-joiner"),config,group,joinerKey).serveAllInbound();
		resources.add(joiner);
		joiner.launch();

		joiner.seed(P2PNode.seeds(config)).get(TIMEOUT,TimeUnit.SECONDS);
		LatticeConnectionManager manager=joiner.propagationGroup().getConnectionManager();
		assertFalse(manager.getDesiredPeers().containsKey(hubKey.getAccountKey()),"only seeding dials the hub again");

		// The joiner's only route goes, so it seeds again
		resources.remove(other);
		other.close();
		joiner.whenConnected(hubKey.getAccountKey()).get(TIMEOUT,TimeUnit.SECONDS);
	}

	@Test
	public void testRestartedNodeRejoinsFromRetainedRecordsWithoutSeeds() throws Exception {
		P2PNode hub=launch(store("seed-hub"),NodeConfig.localNetwork(),0,hubKey);
		EtchStore joinerStore=store("seed-joiner");
		P2PNode first=P2PNode.create(joinerStore,NodeConfig.localNetwork(),joinerKey).serveAllInbound();
		first.launch();
		first.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS);
		shutDown(first);

		P2PNode restarted=P2PNode.create(joinerStore,NodeConfig.localNetwork(),joinerKey).serveAllInbound();
		resources.add(restarted);
		AHashMap<Keyword,ACell> contact=restarted.getApplication().contacts().get(hubKey.getAccountKey());
		assertTrue(ContactRecords.answered(contact),"the contact record survived the restart");
		assertEquals(address(hub),ContactRecords.address(contact),"with the address the hub proved its key at");
		restarted.launch();
		restarted.whenConnected(hubKey.getAccountKey()).get(TIMEOUT,TimeUnit.SECONDS);
	}

	/** The address a contact record holds is enough to rejoin: this node has no node record for the hub. */
	@Test
	public void testRestartedNodeDialsTheAddressItRecordedForAContact() throws Exception {
		P2PNode hub=launch(store("seed-hub"),NodeConfig.localNetwork(),0,hubKey);
		EtchStore joinerStore=store("seed-joiner");
		P2PNode first=P2PNode.create(joinerStore,NodeConfig.localNetwork(),joinerKey).serveAllInbound();
		first.launch();
		first.getApplication().contacts().contacted(hubKey.getAccountKey(),address(hub));
		shutDown(first);

		P2PNode restarted=P2PNode.create(joinerStore,NodeConfig.localNetwork(),joinerKey).serveAllInbound();
		resources.add(restarted);
		assertNull(registry(restarted).get(hubKey.getAccountKey()));
		restarted.launch();
		restarted.whenConnected(hubKey.getAccountKey()).get(TIMEOUT,TimeUnit.SECONDS);
	}

	/** Shuts a node down as a service does, keeping its store for a restart. */
	private static void shutDown(P2PNode node) throws Exception {
		try (node; var host=node.getNodeServer().getHost()) {
			node.stopIngress();
			node.getApplication().sync();
		}
	}

	private static AString address(P2PNode node) {
		return Strings.create(Transports.endpoint(node.getHostAddress()).toString());
	}

	@Test
	public void testSeedsAreReadFromConfiguration() {
		assertTrue(P2PNode.seeds(NodeConfig.create()).isEmpty());

		AccountKey first=AKeyPair.createSeeded(1).getAccountKey();
		AccountKey second=AKeyPair.createSeeded(2).getAccountKey();
		NodeConfig config=NodeConfig.create(JSON.parseJSON5("{seeds: [{key: '0x"+first.toHexString()
			+"', url: 'tcp://peer.example.com:19000'}, {key: '"+second.toHexString()+"', url: 'peer.example.com'},"
			+" {url: 'tls://peer.example.com:443'}, {url: 'did:web:files.example.com'}]}"));
		assertEquals(List.of(new PeerName(URI.create("tcp://peer.example.com:19000"),first),
				new PeerName(URI.create("tcp://peer.example.com:"+Constants.DEFAULT_PEER_PORT),second),
				new PeerName(URI.create("tls://peer.example.com:443"),null),
				new PeerName(URI.create("did:web:files.example.com"),null)),
			P2PNode.seeds(config),"in configured order, a key optional and a missing port the default");

		AVector<ACell> tooMany=Vectors.empty();
		for (int i=0; i<=P2PNode.MAX_SEEDS; i++) {
			tooMany=tooMany.conj(Maps.of(Strings.create("key"),Strings.create(AKeyPair.createSeeded(i).getAccountKey().toHexString()),
				Strings.create("url"),Strings.create("tcp://localhost:1")));
		}
		assertInvalid(tooMany);
		assertInvalid(Strings.create("tcp://localhost:1"));
		assertInvalid(Vectors.of(Maps.of(Strings.create("key"),Strings.create(first.toHexString()))));
		assertInvalid(Vectors.of(Maps.of(Strings.create("key"),Strings.create("0x1234"),Strings.create("url"),Strings.create("tcp://localhost:1"))));
		assertInvalid(Vectors.of(Maps.of(Strings.create("url"),Strings.create("did:convex:13"))));
		assertInvalid(Vectors.of(Maps.of(Strings.create("key"),Strings.create(first.toHexString()),Strings.create("url"),Strings.create("tcp://localhost:1/path"))));
	}

	@Test
	public void testLauncherKeyIsAnEd25519Seed() {
		assertNull(P2PNode.keyPair(null));
		AKeyPair key=AKeyPair.createSeeded(7);
		assertEquals(key.getAccountKey(),P2PNode.keyPair(Strings.create(key.getSeed().toHexString())).getAccountKey());
		AString shortSeed=Strings.create("0x1234");
		assertThrows(IllegalArgumentException.class,() -> P2PNode.keyPair(shortSeed));
		assertThrows(IllegalArgumentException.class,() -> P2PNode.keyPair(CVMLong.ONE));
	}

	private static void assertInvalid(ACell seeds) {
		NodeConfig config=NodeConfig.create(Maps.of(P2PNode.SEEDS,seeds));
		assertThrows(IllegalArgumentException.class,() -> P2PNode.seeds(config));
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<ACell,ACell> registry(P2PNode node) {
		ACell nodes=node.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		return (AHashMap<ACell,ACell>)nodes;
	}
}
