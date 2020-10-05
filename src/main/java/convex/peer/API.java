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
import convex.net.ResultConsumer;

/**
 * Class providing a simple API to a peer Server.
 * 
 * Hopefully suitable for library usage.
 */
public class API {

	private static final Logger log = Logger.getLogger(API.class.getName());

	public static Server launchPeer() {
		Map<Keyword, Object> config = new HashMap<>();
		return launchPeer(config);
	}
	
	/**
	 * Launches a Peer Server with a default configuration.
	 * 
	 * Config keys are:
	 * 
	 * :port (optional) - Integer port number to use for incoming connections. Defaults to random.
	 * :store (optional) - AStore instance. Defaults to Stores.DEFAULT, a temporary Etch store
	 * :keypair (optional) - AKeyPair instance. Defaults to first auto-generated Peer keyPair;
	 * :state (optional) - Initialisation state. Only used if initialising a new Peer.
	 * :restore (optional) - Boolean Flag to restore from existing store. Default to true
	 * 
	 * @return New Server instance
	 */
	public static Server launchPeer(Map<Keyword, Object> peerConfig) {
		HashMap<Keyword,Object> config=new HashMap<>(peerConfig);
		try {
			if (!config.containsKey(Keywords.PORT)) config.put(Keywords.PORT, null);
			if (!config.containsKey(Keywords.STORE)) config.put(Keywords.STORE, Stores.DEFAULT);
			if (!config.containsKey(Keywords.KEYPAIR)) config.put(Keywords.KEYPAIR, Init.KEYPAIRS[0]);
			if (!config.containsKey(Keywords.STATE)) config.put(Keywords.STATE, Init.STATE);
			if (!config.containsKey(Keywords.RESTORE)) config.put(Keywords.RESTORE, true);

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

		@Override
		protected void handleError(long id, Object code, Object msg) {
			log.info("ERROR RECEIVED: " + code + " : " + msg);
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
