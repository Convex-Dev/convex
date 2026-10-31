package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
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
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;

/**
 * Discovered nodes are connection candidates, never wanted peers: however many
 * records discovery holds, they cannot stop an operator's connection or a joining
 * node's return route.
 */
public class P2PDiscoveryTest {

	private static final int CANDIDATES=4;

	private final AKeyPair hubKey=AKeyPair.generate();
	private final AKeyPair peerKey=AKeyPair.generate();
	private final AKeyPair leafKey=AKeyPair.generate();

	private EtchStore hubStore,peerStore,leafStore;
	private P2PNode hub,peer,leaf;

	@AfterEach
	public void tearDown() throws Exception {
		if (leaf!=null) leaf.close();
		if (peer!=null) peer.close();
		if (hub!=null) hub.close();
		if (leafStore!=null) leafStore.close();
		if (peerStore!=null) peerStore.close();
		if (hubStore!=null) hubStore.close();
	}

	@Test
	public void testFullCandidatePoolBlocksNoConnection() throws Exception {
		hubStore=EtchStore.createTemp("p2p-discovery-hub");
		peerStore=EtchStore.createTemp("p2p-discovery-peer");
		// No ambient slots: the hub keeps candidates but dials none of them
		LatticePropagatorConfig bounded=LatticePropagatorConfig.create(Maps.of(
			LatticePropagatorConfig.MAX_DESIRED_PEERS,CVMLong.create(CANDIDATES),
			LatticePropagatorConfig.AMBIENT_PEERS,CVMLong.create(0)));
		hub=P2PNode.create(hubStore,NodeConfig.localNetwork(),bounded,hubKey).serveAllInbound();
		peer=P2PNode.create(peerStore,NodeConfig.localNetwork(),
			LatticePropagatorConfig.create(),peerKey).serveAllInbound();
		hub.launch();
		peer.launch();

		// More valid dialable records than the hub has candidate slots, offered over
		// an unverified public connection. Discovery runs before the reply.
		AHashMap<ACell,SignedData<ACell>> records=Maps.empty();
		for (int i=0; i<2*CANDIDATES; i++) {
			records=records.merge(convex.lattice.p2p.P2PLattice.createSignedEntry(AKeyPair.generate(),
				convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.of(Strings.create("tcp://localhost:1")),
					Strings.create("test"),Strings.create("test"),null,1L)));
		}
		Message update=Message.create(MessageType.LATTICE_VALUE,Vectors.create(
			MessageTag.LATTICE_VALUE,null,NodeDirectory.REGISTRY_PATH,records));
		try (Convex sender=Convex.connect(hub.getHostAddress(),null,AKeyPair.generate())) {
			assertFalse(sender.request(update).get(5,TimeUnit.SECONDS).isError());
		}
		assertEquals(CANDIDATES,hub.propagationGroup().getConnectionManager().getDesiredPeers().size());

		// The operator's own connection is not a candidate and still succeeds
		Convex toPeer=hub.connect(peerKey.getAccountKey(),peer.getHostAddress())
			.get(5,TimeUnit.SECONDS);
		assertEquals(peerKey.getAccountKey(),toPeer.getVerifiedPeer());

		// An outbound-only node joining later still gets its return route
		leafStore=EtchStore.createTemp("p2p-discovery-leaf");
		leaf=P2PNode.create(leafStore,NodeConfig.port(-1),LatticePropagatorConfig.create(),leafKey);
		leaf.launch();
		leaf.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(5,TimeUnit.SECONDS);
		AConnection route=hub.whenInboundConnectionUpgraded(leafKey.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		assertEquals(leafKey.getAccountKey(),route.getTrustedKey());
	}
}
