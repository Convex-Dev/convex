package convex.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.Constants;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.PeerStatus;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

public class PeerCluster {

	private static final Logger log = Logger.getLogger(PeerCluster.class.getName());

	public static final int NUM_PEERS = 5;
	public static final ArrayList<Map<Keyword, Object>> PEER_CONFIGS = new ArrayList<>(NUM_PEERS);
	public static final ArrayList<AKeyPair> PEER_KEYS = new ArrayList<>(NUM_PEERS);

	static {
		// create a key pair for each peer
		for (int i = 0; i < NUM_PEERS; i++) {
			PEER_KEYS.add(Ed25519KeyPair.createSeeded(1000+i));
		}

		// create configuration maps for each peer
		for (int i = 0; i < NUM_PEERS; i++) {
			int port = Server.DEFAULT_PORT + i;
			Map<Keyword, Object> config = new HashMap<>();
			config.put(Keywords.PORT, port);
			config.put(Keywords.KEYPAIR, PEER_KEYS.get(i));
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
		BlobMap<AccountKey, PeerStatus> peers = BlobMaps.empty();
		for (int i = 0; i < NUM_PEERS; i++) {
			AccountKey peerKey = PEER_KEYS.get(i).getAccountKey();
			Map<Keyword, Object> config = PEER_CONFIGS.get(i);
			int port = Utils.toInt(config.get(Keywords.PORT));
			AString sa = Strings.create("http://localhost"+ port);
			Address address = Address.create(i);
			PeerStatus ps = PeerStatus.create(address, 1000000000, sa);
			peers = peers.assoc(peerKey, ps);

			AccountStatus as = AccountStatus.create(1000000000,peerKey);
			accts = accts.conj(as);
		}

		return State.create(accts, peers, Constants.INITIAL_GLOBALS, BlobMaps.empty());
	}

	public static void main(String... args) {
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
					log.warning("Sleep interrupted?");
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
