package convex.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;

public class LatticeProtocolEndpointTest {

	/** A closed endpoint turns away a new connection, and asks no peer for data it lacks. */
	@Test
	public void testClosedEndpointTurnsAwayConnectionsAndFetches() throws Exception {
		var config=LatticePropagatorConfig.create();
		var group=new LatticePropagator(new MemoryStore(),config);
		List<Message> sent=new CopyOnWriteArrayList<>();
		var connection=LocalConnection.createPair(message -> true,message -> { sent.add(message); return true; });
		connection.setTrustedKey(AKeyPair.generate().getAccountKey());
		var endpoint=new LatticeProtocolEndpoint(group,null,config);
		endpoint.start();
		endpoint.close();

		assertThrows(IllegalStateException.class,() -> endpoint.attachInbound(connection));
		assertFalse(endpoint.ownsInbound(connection),"a connection turned away is not kept");

		// A value whose branch the group's store lacks, received as bytes
		var branch=Blob.wrap(new byte[400]);
		var value=Cells.persist(Sets.of(branch),new MemoryStore());
		var payload=Vectors.create(MessageTag.LATTICE_VALUE,CVMLong.create(-1),Vectors.empty(),value);
		endpoint.handle(Message.create(connection,payload.getEncoding()));
		assertTrue(sent.stream().noneMatch(message -> message.getType()==MessageType.DATA_REQUEST),
			"a closed group starts no fetch");
	}
}
