package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.Init;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.lang.Reader;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;

/**
 * Class providing a simple API to a peer Server.
 * 
 * Hopefully suitable for library usage.
 */
public class API {

	private static final Logger log = Logger.getLogger(API.class.getName());

	/**
	 * Launches a Peer Server with a default configuration.
	 * @return New Server instance
	 */
	public static Server launchPeer() {
		Map<Keyword, Object> config = new HashMap<>();

		try {
			config.put(Keywords.PORT, null);
			config.put(Keywords.STORE, Stores.DEFAULT);
			config.put(Keywords.KEYPAIR, Init.KEYPAIRS[0]);
			config.put(Keywords.STATE, Init.INITIAL_STATE);

			Server server = Server.create(config);
			server.launch();
			return server;
		} catch (Throwable t) {
			t.printStackTrace();
			throw Utils.sneakyThrow(t);
		}
	}

	private static final ResultConsumer resultConsumer = new ResultConsumer() {

		@Override
		protected void handleResult(long id, Object m) {
			log.info("RESULT RECEIVED: " + m);
		}

		protected void handleError(long id, Message m) {
			log.info("ERROR RECEIVED: " + m.getErrorType() + " : " + m.getPayload());
		}

	};

	/**
	 * Demo of key API functions for user application
	 * 
	 * @param args
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public static void main(String... args) throws InterruptedException, IOException {
		Server s = launchPeer();
		InetSocketAddress hostAddress = s.getHostAddress();
		log.info("Launched server at address: " + hostAddress);

		// connect with the specified consumer for callbacks
		Connection c = Connection.connect(hostAddress, resultConsumer, Stores.CLIENT_STORE);

		// send a query as the HERO user
		long queryID = c.sendQuery(Reader.read("(+ 1 2)"), Init.HERO);
		log.info("Sent query ith ID: " + queryID);

		// send a transaction as the HERO user
		// we need the next sequence number for transaction to be valid
		long seq = s.getPeer().getConsensusState().getAccount(Init.HERO).getSequence();
		ATransaction tx = Invoke.create(seq + 1, Reader.read("*balance*"));
		long txID = c.sendTransaction(Init.HERO_KP.signData(tx));
		log.info("Sent transaction with ID: " + txID);

		// wait a bit
		Thread.sleep(5000);

		// shutdown
		log.info("Triggering shutdown after 5s");
		s.close();
		Thread.sleep(500);
	}
}
