package convex.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.message.LocalConnection;
import convex.core.store.MemoryStore;

public class LatticeProtocolEndpointTest {

	/** A closed endpoint turns away a new connection and does not keep it. */
	@Test
	public void testClosedEndpointTurnsAwayConnections() throws Exception {
		var config=LatticePropagatorConfig.create();
		var group=new LatticePropagator(new MemoryStore(),config);
		var connection=LocalConnection.createPair(message -> true,message -> true);
		var endpoint=new LatticeProtocolEndpoint(group,null,config);
		endpoint.start();
		endpoint.close();

		assertThrows(IllegalStateException.class,() -> endpoint.attachInbound(connection));
		assertFalse(endpoint.ownsInbound(connection),"a connection turned away is not kept");
	}
}
