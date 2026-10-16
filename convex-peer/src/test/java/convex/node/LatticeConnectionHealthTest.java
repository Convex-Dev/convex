package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;

/** Actual PING correlation over TCP, including the return path of an outbound-only node. */
public class LatticeConnectionHealthTest {
	@Test
	public void testProbeCancellationAndInvalidResponseReleaseRequests() throws Exception {
		var config=LatticePropagatorConfig.create();
		var group=new LatticePropagator(new MemoryStore(),config);
		var endpoint=new LatticeProtocolEndpoint(group,null,config);
		var sent=new java.util.ArrayList<Message>();
		var connection=LocalConnection.createPair(message -> {
			try { return message.getConnection().completeRequest(message); }
			catch (Exception e) { throw new AssertionError(e); }
		},message -> { sent.add(message); return true; });
		try {
			var cancelled=endpoint.probe(connection);
			assertTrue(cancelled.cancel(false));
			assertFalse(connection.hasPendingRequests());
			assertFalse(sent.getFirst().returnResult(Result.value(CVMLong.ONE)));

			var invalid=endpoint.probe(connection);
			assertTrue(sent.getLast().returnResult(Result.value(null)));
			var failure=assertThrows(java.util.concurrent.ExecutionException.class,() -> invalid.get(5,TimeUnit.SECONDS));
			assertInstanceOf(IllegalStateException.class,failure.getCause());
			assertFalse(connection.hasPendingRequests());

			var valid=endpoint.probe(connection);
			assertTrue(sent.getLast().returnResult(Result.value(CVMLong.ONE)));
			assertEquals(CVMLong.ONE,((Result)valid.get(5,TimeUnit.SECONDS)).getValue());
			assertFalse(connection.hasPendingRequests());
		} finally {
			connection.close();
		}
	}

	@Test
	public void testProbesOverBothRouteDirections() throws Exception {
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
		try (var alice=new NodeServer<>(RelayHost.open(lattice,aliceStore,NodeConfig.port(-1)),NodeConfig.port(-1));
			var bob=new NodeServer<>(RelayHost.open(lattice,bobStore,NodeConfig.localNetwork()),NodeConfig.localNetwork());
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
