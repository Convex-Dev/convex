package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Vectors;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;

public class ServerTest {

	final Server server;
	final AKeyPair keyPair;

	private HashMap<Long, Object> results = new HashMap<>();

	private Consumer<Message> handler = new ResultConsumer() {
		@Override
		protected void handleResult(long id, Object value) {
			results.put(id, value);
		}
	};

	@Test
	public void testServerConnect() throws IOException, InterruptedException {
		InetSocketAddress hostAddress=server.getHostAddress();
		
		// Connect to Peer Server using the current store for the client
		Connection pc = Connection.connect(hostAddress, handler, Stores.current());
		AVector<Long> v = Vectors.of(1l, 2l, 3l);
		long id1 = pc.sendQuery(v);
		Utils.timeout(200, () -> results.get(id1) != null);
		assertEquals(v, results.get(id1));
	}

	{
		keyPair = Init.KEYPAIRS[0];

		Map<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.PORT, 0);
		config.put(Keywords.STATE, Init.STATE);
		config.put(Keywords.KEYPAIR, Init.KEYPAIRS[0]); // use first peer keypair

		server = Server.create(config);
		server.launch();
	}
}
