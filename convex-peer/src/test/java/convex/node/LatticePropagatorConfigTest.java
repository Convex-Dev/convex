package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.generic.MaxLattice;

/** Tests the explicit boundary between node-host and propagation-group limits. */
public class LatticePropagatorConfigTest {

	@Test
	public void testHostAndGroupConfigurationAreIndependent() throws Exception {
		NodeConfig host=NodeConfig.create(Maps.of(
			NodeConfig.PORT,CVMLong.create(-1),
			NodeConfig.MAX_MESSAGE_SIZE,CVMLong.create(4096)));
		LatticePropagatorConfig group=LatticePropagatorConfig.create(Maps.of(
			LatticePropagatorConfig.MAX_MESSAGE_SIZE,CVMLong.create(8192),
			LatticePropagatorConfig.MAX_TRUSTED_MESSAGE_SIZE,CVMLong.create(16384),
			LatticePropagatorConfig.MAX_DESIRED_PEERS,CVMLong.create(3)));

		try (NodeServer<?> node=new NodeServer<>(MaxLattice.create(),new MemoryStore(),host)) {
			LatticePropagator propagator=new LatticePropagator(new MemoryStore(),
				MaxLattice.create(),value -> value,group);
			node.addPropagator(propagator);

			assertEquals(4096,node.getConfig().getMaxMessageSize());
			assertEquals(8192,propagator.getMaxDeltaMessageSize());
			assertEquals(3,propagator.getConnectionManager().getMaxDesiredPeers());
		}
	}

	@Test
	public void testCombinedMapMigrationAdapter() {
		NodeConfig legacy=NodeConfig.create(Maps.of(
			LatticePropagatorConfig.MAX_DELTA_MESSAGE_SIZE,CVMLong.create(8192),
			LatticePropagatorConfig.MAX_DELTA_BROADCAST_SIZE,CVMLong.create(32768),
			LatticePropagatorConfig.INBOUND_QUEUE_SIZE,CVMLong.create(17)));
		LatticePropagatorConfig group=LatticePropagatorConfig.from(legacy);

		assertEquals(8192,group.getMaxDeltaMessageSize());
		assertEquals(32768,group.getMaxDeltaBroadcastSize());
		assertEquals(17,group.getInboundQueueSize());
	}
}
