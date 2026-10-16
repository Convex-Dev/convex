package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;

/**
 * A group whose only route is an authenticated inbound connection upgraded to
 * an outbound route must broadcast each publication over it, not leave the
 * peer to the periodic root sync.
 */
public class UpgradedInboundRouteBroadcastTest {

	@Test
	public void testPublicationsReachAPeerThatDialledIn() throws Exception {
		var aliceKey=AKeyPair.generate();
		var bobKey=AKeyPair.generate();
		var aliceStore=new MemoryStore();
		var bobStore=new MemoryStore();
		var lattice=MaxLattice.create();
		var aliceGroup=new LatticePropagator(aliceStore,LatticePropagatorConfig.create());
		var bobGroup=new LatticePropagator(bobStore,LatticePropagatorConfig.create());
		aliceGroup.setTransportKeyPair(aliceKey);
		bobGroup.setTransportKeyPair(bobKey);
		var incoming=new CompletableFuture<AConnection>();
		var firstReceived=new CompletableFuture<ACell>();
		var secondReceived=new CompletableFuture<ACell>();
		aliceGroup.setInboundLatticeListener((connection,group,path,value,changed) -> {
			if (CVMLong.create(1).equals(value)) firstReceived.complete(value);
			if (CVMLong.create(2).equals(value)) secondReceived.complete(value);
		});
		try (var alice=new NodeServer<>(lattice,aliceStore,NodeConfig.port(-1));
			var bob=new NodeServer<>(lattice,bobStore,NodeConfig.localNetwork());
			var listener=new LatticeListener(NodeConfig.localNetwork())) {
			alice.addPropagator(aliceGroup);
			bob.addPropagator(bobGroup);
			listener.registerPropagator(bobGroup);
			listener.setSelector(connection -> { incoming.complete(connection); return bobGroup; });
			alice.launch();
			bob.launch();
			listener.launch();

			// Alice dials Bob; Bob never dials Alice. Bob's only route to her is her socket.
			var bobManager=bobGroup.getConnectionManager();
			bobManager.addPeer(aliceKey.getAccountKey());
			aliceGroup.getConnectionManager().connectPeer(bobKey.getAccountKey(),
				new InetSocketAddress("localhost",listener.getPort())).get(5,TimeUnit.SECONDS);
			bobGroup.authenticateInboundRoute(incoming.get(5,TimeUnit.SECONDS),aliceKey.getAccountKey());
			bobManager.whenInboundConnectionUpgraded(aliceKey.getAccountKey()).get(5,TimeUnit.SECONDS);
			assertTrue(bobManager.hasPropagationRoutes());
			assertTrue(bobManager.getPeers().isEmpty(),"the upgraded route is not an outbound client");

			assertPublication(bob,bobGroup,firstReceived,1);
			assertPublication(bob,bobGroup,secondReceived,2);
		}
	}

	private static void assertPublication(NodeServer<AInteger> bob,LatticePropagator group,
			CompletableFuture<ACell> received,long expected) throws Exception {
		var value=CVMLong.create(expected);
		long broadcasts=group.getBroadcastCount();
		var announced=group.nextAnnounce();
		bob.getCursor().set(value);
		bob.getHost().sync();
		assertEquals(value,announced.get(5,TimeUnit.SECONDS));
		assertTrue(group.getBroadcastCount()>broadcasts,
			"publication must trigger a delta broadcast, independent of periodic root sync");
		assertEquals(value,received.get(5,TimeUnit.SECONDS));
	}
}
