package convex.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.PeerException;
import convex.peer.Server;

public class PeerCluster {

	private static final Logger log = LoggerFactory.getLogger(PeerCluster.class.getName());

	public static final int NUM_PEERS = 5;
	public static final ArrayList<Map<Keyword, Object>> PEER_CONFIGS = new ArrayList<>(NUM_PEERS);
	public static final ArrayList<AKeyPair> PEER_KEYPAIRS = new ArrayList<>(NUM_PEERS);

	static {
		// create a key pair for each peer
		for (int i = 0; i < NUM_PEERS; i++) {
			PEER_KEYPAIRS.add(AKeyPair.createSeeded(1000+i));
		}

		// create configuration maps for each peer
		for (int i = 0; i < NUM_PEERS; i++) {
			int port = Server.DEFAULT_PORT + i;
			Map<Keyword, Object> config = new HashMap<>();
			config.put(Keywords.PORT, port);
			config.put(Keywords.KEYPAIR, PEER_KEYPAIRS.get(i));
			PEER_CONFIGS.add(config);
		}

		State initialState = createInitialState();

		for (int i = 0; i < NUM_PEERS; i++) {
			Map<Keyword, Object> config = PEER_CONFIGS.get(i);
			config.put(Keywords.STATE, initialState);
		}
	}

	private static State createInitialState() {
		// setting up NUM_PEERS peers with accounts 0..NUM_PEERS-1
		AVector<AccountStatus> accts = Vectors.empty();
		Index<AccountKey, PeerStatus> peers = State.EMPTY_PEERS;
		for (int i = 0; i < NUM_PEERS; i++) {
			AccountKey peerKey = PEER_KEYPAIRS.get(i).getAccountKey();
			Map<Keyword, Object> config = PEER_CONFIGS.get(i);
			int port = Utils.toInt(config.get(Keywords.PORT));
			AString urlString = Strings.create("http://localhost"+ port);
			Address address = Address.create(i);
			PeerStatus ps = PeerStatus.create(address, 1000000000, Maps.create(Keywords.URL,urlString));
			peers = peers.assoc(peerKey, ps);

			AccountStatus as = AccountStatus.create(1000000000,peerKey);
			accts = accts.conj(as);
		}

		return State.create(accts, peers, Constants.INITIAL_GLOBALS, State.EMPTY_SCHEDULE);
	}

	public static void main(String... args) throws InterruptedException, PeerException {
		ArrayList<Server> peers = new ArrayList<>(NUM_PEERS);

		log.info("Creating peer configurations");
		for (Map<Keyword, Object> config : PEER_CONFIGS) {
			peers.add(API.launchPeer(config));
		}

		try {
			log.info("Peers launched");

			while (true) {
				try {
					Thread.sleep(1000);
					// Log.info("Waiting...");
				} catch (InterruptedException e) {
					log.warn("Sleep interrupted?");
					return;
				}
			}
		} finally {
			log.info("Server stopping....");
			for (Server peer : peers) {
				peer.close();
			}
			log.info("Server stopped successfully");
		}
	}

}
