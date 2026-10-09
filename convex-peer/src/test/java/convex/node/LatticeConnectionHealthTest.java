package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.message.AConnection;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;

/** Actual PING correlation over TCP, including the return path of an outbound-only node. */
public class LatticeConnectionHealthTest {
	@Test
	public void testProbesOverBothRouteDirections() throws Exception {
		var aliceKey=AKeyPair.generate();
		var bobKey=AKeyPair.generate();
		var aliceStore=new MemoryStore();
		var bobStore=new MemoryStore();
		var lattice=MaxLattice.create();
		var aliceGroup=new LatticePropagator(aliceStore,lattice,value -> value,LatticePropagatorConfig.create());
		var bobGroup=new LatticePropagator(bobStore,lattice,value -> value,LatticePropagatorConfig.create());
		aliceGroup.setTransportKeyPair(aliceKey);
		bobGroup.setTransportKeyPair(bobKey);
		var incoming=new CompletableFuture<AConnection>();
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
			var aliceManager=aliceGroup.getConnectionManager();
			var bobManager=bobGroup.getConnectionManager();
			bobManager.addPeer(aliceKey.getAccountKey());
			var outbound=aliceManager.connectPeer(bobKey.getAccountKey(),
				new InetSocketAddress("localhost",listener.getPort())).get(5,TimeUnit.SECONDS);
			AConnection inbound=incoming.get(5,TimeUnit.SECONDS);
			bobGroup.authenticateInboundRoute(inbound,aliceKey.getAccountKey());
			bobManager.whenInboundConnectionUpgraded(aliceKey.getAccountKey()).get(5,TimeUnit.SECONDS);
			assertNotNull(aliceManager.probePeer(outbound).get(5,TimeUnit.SECONDS));
			assertNotNull(bobManager.probePeer(inbound).get(5,TimeUnit.SECONDS));
			assertTrue(outbound.isConnected());
			assertSame(inbound,bobManager.getUpgradedInboundConnection(aliceKey.getAccountKey()));
		}
	}
}
