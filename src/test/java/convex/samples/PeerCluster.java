package convex.samples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.core.State;
import convex.core.crypto.ECDSAKeyPair;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Sets;
import convex.core.util.Utils;
import convex.peer.Server;

public class PeerCluster {

	private static final Logger log = Logger.getLogger(PeerCluster.class.getName());

	public static final int NUM_PEERS = 5;
	public static final ArrayList<Map<Keyword, Object>> PEER_CONFIGS = new ArrayList<>(NUM_PEERS);
	public static final ArrayList<ECDSAKeyPair> PEER_KEYS = new ArrayList<>(NUM_PEERS);

	static {
		// create a key pair for each peer
		for (int i = 0; i < NUM_PEERS; i++) {
			PEER_KEYS.add(ECDSAKeyPair.generate());
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
		BlobMap<Address, AccountStatus> accts = BlobMaps.empty();
		BlobMap<Address, PeerStatus> peers = BlobMaps.empty();
		for (int i = 0; i < NUM_PEERS; i++) {
			Address address = PEER_KEYS.get(i).getAddress();
			Map<Keyword, Object> config = PEER_CONFIGS.get(i);
			int port = Utils.toInt(config.get(Keywords.PORT));
			String sa = "http://localhost"+ port;
			PeerStatus ps = PeerStatus.create(Amount.create(1000000000), sa);
			AccountStatus as = AccountStatus.create(Amount.create(1000000000));
			peers = peers.assoc(address, ps);
			accts = accts.assoc(address, as);
		}

		return State.create(accts, peers, Sets.empty(), Maps.empty(), BlobMaps.empty());
	}

	public static void main(String... args) {
		ArrayList<Server> peers = new ArrayList<>(NUM_PEERS);

		log.info("Creating peer configurations");
		for (Map<Keyword, Object> config : PEER_CONFIGS) {
			peers.add(Server.create(config));
		}

		log.info("Server starting....");
		for (Server peer : peers) {
			peer.launch();
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
