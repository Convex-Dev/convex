package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.etch.EtchStore;
import convex.lattice.LocalLattice;
import convex.net.Transports;
import convex.node.LatticeConnectionManager.DesiredPeer;
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;

/**
 * A node's blocklist at work: it survives a restart, and a blocked node or address
 * gets no connection, no candidacy and no messages.
 */
public class P2PBlocklistTest {

	private static final long TIMEOUT=10;

	private final AKeyPair hubKey=AKeyPair.generate();
	private final AKeyPair leafKey=AKeyPair.generate();
	private final List<AutoCloseable> resources=new ArrayList<>();

	@AfterEach
	public void tearDown() throws Exception {
		Collections.reverse(resources);
		for (AutoCloseable resource:resources) resource.close();
	}

	private static AString s(String text) {
		return Strings.create(text);
	}

	private EtchStore store(String name) throws Exception {
		EtchStore store=EtchStore.createTemp(name);
		resources.add(store);
		return store;
	}

	private P2PNode launch(String name,NodeConfig config,LatticePropagatorConfig group,AKeyPair key) throws Exception {
		P2PNode node=P2PNode.create(store(name),config,group,key).serveAllInbound();
		resources.add(node);
		node.launch();
		return node;
	}

	private P2PNode hub() throws Exception {
		return launch("blocklist-hub",NodeConfig.localNetwork(),LatticePropagatorConfig.create(),hubKey);
	}

	private P2PNode leaf() throws Exception {
		return launch("blocklist-leaf",NodeConfig.port(-1),LatticePropagatorConfig.create(),leafKey);
	}

	/** Shuts a node down as a service does, keeping its store. */
	private static void closeKeepingStore(P2PNode node) throws Exception {
		try (node; var host=node.getNodeServer().getHost()) {
			node.getApplication().sync();
		}
	}

	@Test
	public void testABlockAndAnUnblockSurviveARestart() throws Exception {
		EtchStore store=store("blocklist-restart");
		AccountKey bob=AKeyPair.generate().getAccountKey();
		P2PNode first=P2PNode.create(store,NodeConfig.localNetwork(),hubKey);
		assertTrue(first.block(bob));
		assertTrue(first.block(s("10.0.0.9")));
		closeKeepingStore(first);

		P2PNode second=P2PNode.create(store,NodeConfig.localNetwork(),hubKey);
		assertTrue(second.isBlocked(bob));
		assertTrue(second.isBlocked(s("10.0.0.9")));
		assertTrue(second.unblock(bob));
		closeKeepingStore(second);

		P2PNode third=P2PNode.create(store,NodeConfig.localNetwork(),hubKey);
		resources.add(third);
		assertFalse(third.isBlocked(bob),"the removal carried over too");
		assertTrue(third.isBlocked(s("10.0.0.9")));
	}

	@Test
	public void testANodeWithoutAKeyKeepsItsBlocklistInMemory() throws Exception {
		P2PNode node=P2PNode.create(store("blocklist-keyless"),NodeConfig.localNetwork(),null);
		resources.add(node);
		AccountKey bob=AKeyPair.generate().getAccountKey();
		assertTrue(node.block(bob));
		assertTrue(node.isBlocked(bob));
		assertNull(node.getCursor().get(LocalLattice.KEY_LOCAL));
	}

	/** Discovery offers no blocked node, and a node only at the addresses this node does not refuse. */
	@Test
	public void testDiscoveryOffersNoBlockedNodeOrAddress() throws Exception {
		// No ambient slots: the hub keeps candidates but dials none of them
		P2PNode hub=launch("blocklist-hub",NodeConfig.localNetwork(),LatticePropagatorConfig.create(Maps.of(
			LatticePropagatorConfig.AMBIENT_PEERS,CVMLong.create(0))),hubKey);
		AKeyPair blockedKey=AKeyPair.generate();
		AccountKey blockedNode=blockedKey.getAccountKey();
		AKeyPair[] keys=new AKeyPair[] {AKeyPair.generate(),AKeyPair.generate(),AKeyPair.generate()};
		AccountKey onlyBlocked=keys[0].getAccountKey(),partly=keys[1].getAccountKey(),open=keys[2].getAccountKey();
		hub.block(blockedNode);
		hub.block(s("blocked.example"));

		AHashMap<ACell,SignedData<ACell>> records=Maps.empty();
		records=records.merge(record(blockedKey,s("tcp://localhost:1")));
		records=records.merge(record(keys[0],s("tcp://blocked.example:1")));
		records=records.merge(record(keys[1],s("tcp://blocked.example:1"),s("tcp://localhost:2")));
		records=records.merge(record(keys[2],s("tcp://localhost:3")));
		offer(hub,records);

		Map<AccountKey,DesiredPeer> desired=candidates(hub);
		assertFalse(desired.containsKey(blockedNode));
		assertFalse(desired.containsKey(onlyBlocked),"no address left to dial");
		assertEquals(Vectors.of(s("tcp://localhost:2")),desired.get(partly).transports);
		assertTrue(desired.containsKey(open));

		hub.block(s("tcp://localhost:3"));
		assertFalse(candidates(hub).containsKey(open));
		hub.unblock(s("tcp://localhost:3"));
		assertTrue(candidates(hub).containsKey(open));

		hub.unblock(s("blocked.example"));
		desired=candidates(hub);
		assertTrue(desired.containsKey(onlyBlocked));
		assertEquals(Vectors.of(s("tcp://blocked.example:1"),s("tcp://localhost:2")),desired.get(partly).transports);
	}

	private static AHashMap<ACell,SignedData<ACell>> record(AKeyPair key,AString... transports) {
		return P2PLattice.createSignedEntry(key,P2PLattice.createNodeInfo(Vectors.of((Object[])transports),
			s("test"),s("test"),null,1L));
	}

	private static Map<AccountKey,DesiredPeer> candidates(P2PNode node) {
		return node.propagationGroup().getConnectionManager().getDesiredPeers();
	}

	/** Offers registry records over an unverified connection; discovery runs before the reply. */
	private static void offer(P2PNode node,AHashMap<ACell,SignedData<ACell>> records) throws Exception {
		try (Convex sender=Convex.connect(node.getHostAddress(),null,AKeyPair.generate())) {
			assertFalse(sender.request(update(records)).get(TIMEOUT,TimeUnit.SECONDS).isError());
		}
	}

	private static Message update(AHashMap<ACell,SignedData<ACell>> records) {
		return Message.create(MessageType.LATTICE_VALUE,Vectors.create(
			MessageTag.LATTICE_VALUE,null,NodeDirectory.REGISTRY_PATH,records));
	}

	@Test
	public void testABlockedIPAddressIsRefusedInbound() throws Exception {
		P2PNode hub=hub();
		hub.block(s("127.0.0.1"));
		hub.block(s("::1"));
		Result refused=request(hub);
		assertTrue(refused.isError());
		assertEquals(ErrorCodes.TRUST,refused.getErrorCode());

		hub.unblock(s("127.0.0.1"));
		hub.unblock(s("::1"));
		assertFalse(request(hub).isError(),"the listener reads the list as it is now");
	}

	private static Result request(P2PNode node) throws Exception {
		try (Convex sender=Convex.connect(node.getHostAddress(),null,AKeyPair.generate())) {
			return sender.request(update(Maps.empty())).get(TIMEOUT,TimeUnit.SECONDS);
		}
	}

	@Test
	public void testConnectingToABlockedNodeOrAddressFails() throws Exception {
		P2PNode hub=hub();
		P2PNode leaf=leaf();
		leaf.block(hubKey.getAccountKey());
		assertBlocked(leaf,hub);
		leaf.unblock(hubKey.getAccountKey());

		AString address=s(Transports.endpoint(hub.getHostAddress()).toString());
		leaf.block(address);
		assertBlocked(leaf,hub);
		leaf.unblock(address);

		assertEquals(hubKey.getAccountKey(),leaf.connect(hubKey.getAccountKey(),hub.getHostAddress())
			.get(TIMEOUT,TimeUnit.SECONDS).getVerifiedPeer());
	}

	private void assertBlocked(P2PNode node,P2PNode target) {
		var failure=assertThrows(ExecutionException.class,
			() -> node.connect(hubKey.getAccountKey(),target.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS));
		assertInstanceOf(SecurityException.class,failure.getCause());
	}

	@Test
	public void testBlockingAConnectedNodeClosesItsRoute() throws Exception {
		P2PNode hub=hub();
		P2PNode leaf=leaf();
		leaf.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS);
		AConnection route=hub.whenInboundConnectionUpgraded(leafKey.getAccountKey()).get(TIMEOUT,TimeUnit.SECONDS);

		assertTrue(hub.block(leafKey.getAccountKey()));
		assertTrue(route.isClosed());
		assertFalse(hub.propagationGroup().getConnectionManager().getAuthenticatedRouteKeys()
			.contains(leafKey.getAccountKey()));
		assertNull(hub.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES,leafKey.getAccountKey()));
	}

	/** A blocked node that connects is closed once it claims its key, before any challenge. */
	@Test
	public void testABlockedNodeConnectingInIsClosed() throws Exception {
		P2PNode hub=hub();
		hub.block(leafKey.getAccountKey());
		P2PNode leaf=leaf();
		assertThrows(ExecutionException.class,
			() -> leaf.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS));
		// The hub handles the pushed record before it replies, so no challenge has started
		assertFalse(hub.propagationGroup().getAuthenticatingInboundKeys().contains(leafKey.getAccountKey()));
		assertFalse(hub.propagationGroup().getConnectionManager().getAuthenticatedRouteKeys()
			.contains(leafKey.getAccountKey()));
	}

	/** No message goes to a blocked node, or through one. */
	@Test
	public void testNoMessageGoesToOrThroughABlockedNode() throws Exception {
		AKeyPair daveKey=AKeyPair.generate();
		AccountKey pop=hubKey.getAccountKey(),dave=daveKey.getAccountKey();
		P2PNode hub=P2PNode.create(store("blocklist-pop"),NodeConfig.localNetwork(),hubKey)
			.serveAllInbound().relayMessages();
		resources.add(hub);
		hub.launch();
		P2PNode alice=P2PNode.create(store("blocklist-alice"),NodeConfig.port(-1),leafKey).pointsOfPresence(pop);
		resources.add(alice);
		alice.launch();
		P2PNode daveNode=P2PNode.create(store("blocklist-dave"),NodeConfig.port(-1),daveKey).pointsOfPresence(pop);
		resources.add(daveNode);
		daveNode.launch();
		alice.connect(pop,hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS);
		daveNode.connect(pop,hub.getHostAddress()).get(TIMEOUT,TimeUnit.SECONDS);
		hub.whenInboundConnectionUpgraded(leafKey.getAccountKey()).get(TIMEOUT,TimeUnit.SECONDS);
		hub.whenInboundConnectionUpgraded(dave).get(TIMEOUT,TimeUnit.SECONDS);
		assertTrue(alice.sendMessage(dave,s("hello")));

		alice.block(dave);
		assertFalse(alice.sendMessage(dave,s("hello")));
		alice.unblock(dave);
		assertTrue(alice.sendMessage(dave,s("hello")));

		alice.block(pop);
		assertFalse(alice.sendMessage(dave,s("hello")),"the only route is through a blocked node");
	}
}
