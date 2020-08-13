package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Vectors;
import convex.core.lang.Reader;
import convex.core.store.Stores;
import convex.core.transactions.Invoke;
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
		protected synchronized void handleResult(long id, Object value) {
			results.put(id, value);
		}
		
		@Override
		protected synchronized void handleError(long id, Object code, Object message) {
			results.put(id, code);
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
	
	@Test
	public void testServerTransactions() throws IOException, InterruptedException {
		InetSocketAddress hostAddress=server.getHostAddress();
		
		// Connect to Peer Server using the current store for the client
		Connection pc = Connection.connect(hostAddress, handler, Stores.current());
		
		long id1 = pc.sendTransaction(keyPair.signData(Invoke.create(1, Reader.read("[1 2 3]"))));
		long id2 = pc.sendTransaction(keyPair.signData(Invoke.create(2, Reader.read("(return 2)"))));
		long id2a = pc.sendTransaction(keyPair.signData(Invoke.create(2, Reader.read("22"))));
		long id3 = pc.sendTransaction(keyPair.signData(Invoke.create(3, Reader.read("(rollback 3)"))));
		Utils.timeout(200, () -> results.get(id3) != null);
		
		AVector<Long> v = Vectors.of(1l, 2l, 3l);
		assertEquals(v, results.get(id1));
		assertEquals(2L, results.get(id2));
		assertEquals(ErrorCodes.SEQUENCE, results.get(id2a));
		assertEquals(3L, results.get(id3));
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
