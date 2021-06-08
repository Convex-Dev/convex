package convex.cli.peer;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.List;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;


import convex.api.Shutdown;
import convex.cli.Helpers;
import convex.core.data.Address;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Hash;
import convex.core.crypto.AKeyPair;
import convex.core.store.AStore;
import convex.core.Init;
import convex.core.store.Stores;
import convex.core.Order;
import convex.core.State;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.IServerEvent;
import convex.peer.Server;
import convex.peer.ServerInformation;
import etch.EtchStore;


/**
*
* Convex PeerManager
*
*/

public class PeerManager implements IServerEvent {

	private static final Logger log = Logger.getLogger(PeerManager.class.getName());

	protected List<Server> peerServerList = new ArrayList<Server>();

	protected Session session = new Session();

	protected String sessionFilename;

	protected BlockingQueue<ServerInformation> serverEventQueue = new ArrayBlockingQueue<ServerInformation>(100);


	private PeerManager(String sessionFilename) {
        this.sessionFilename = sessionFilename;
	}

	public static PeerManager create(String sessionFilename) {
        return new PeerManager(sessionFilename);
	}
	/**
	 * Launch a set of peers.
	 *
	 * @param count Number of peers to launch.
	 *
	 * @param keyPairs Array of keyPairs for each peer. The length of the array must be >= the count of peers to launch.
	 *
	 */
	public void launchLocalPeers(int count, AKeyPair[] keyPairs, Address peerAddress) {
		peerServerList.clear();

		Server otherServer;
		String remotePeerHostname;

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs[i];
			Server peerServer = launchPeer(keyPair);
		}

		Server genesisServer = peerServerList.get(0);
		Hash networkId = genesisServer.getPeer().getStates().get(0).getHash();
		genesisServer.setNetworkId(networkId);

		// go through 1..count-1 peers and join them all to peer #0
		// do this twice to allow for all of the peers to get all of the address in the group of peers

		for (int i = 1; i < count; i++) {
			AKeyPair keyPair = keyPairs[i];
			otherServer = peerServerList.get(0);
			remotePeerHostname = otherServer.getHostname();
			Address address = Address.create(peerAddress.toLong() + i);
			// join a peer to the peer #0
			peerServerList.get(i).joinNetwork(keyPair, address, remotePeerHostname);
		}
		// wait for the peers to sync
		waitForNetworkReady();

		// now join the first peer #0, to the rest of the network
		otherServer = peerServerList.get(count - 1);
		remotePeerHostname = otherServer.getHostname();
		peerServerList.get(0).joinNetwork(keyPairs[0], peerAddress, remotePeerHostname);
//		System.out.println("done");
	}

	/**
	 * Load in a session from a session file.
	 *
	 * @param sessionFilename Filename to load.
	 *
	 */
	protected void loadSession() {
		File sessionFile = new File(sessionFilename);
		try {
			session.load(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot load the session control file");
		}
	}

	/**
	 * Add a peer to the session list of peers.
	 *
	 * @param peerServer Add the peerServer to the list of peers for this session.
	 *
	 */
	protected void addToSession(Server peerServer) {
		InetSocketAddress peerHostAddress = peerServer.getHostAddress();
		EtchStore store = (EtchStore) peerServer.getStore();

		session.addPeer(
			peerServer.getAddress().toHexString(),
			peerServer.getHostname(),
			store.getFileName()
		);
	}

	/**
	 * Add all peers started in this session to the session list.
	 *
	 */
	protected void addAllToSession() {
		for (Server peerServer: peerServerList) {
			addToSession(peerServer);
		}
	}

	/**
	 * Remove all peers added by this manager from the session list of peers.
	 *
	 */
	protected void removeAllFromSession() {
		for (Server peerServer: peerServerList) {
			session.removePeer(peerServer.getAddress().toHexString());
		}
	}

	/**
	 * Store the session details to file.
	 *
	 * @param sessionFilename Fileneame to save the session.
	 *
	 */
	protected void storeSession() {
		File sessionFile = new File(sessionFilename);
		try {
			Helpers.createPath(sessionFile);
			if (session.getPeerCount() > 0) {
				session.store(sessionFile);
			}
			else {
				sessionFile.delete();
			}
		} catch (IOException e) {
			log.severe("Cannot store the session control data");
		}
	}

	/**
	 * Launch a local peer on a random port number.
	 *
	 * @param keyPair KeyPair to use for the lauched peer.
	 *
	 * @return Server object of the launched peer.
	 *
	 */
	public Server launchPeer(AKeyPair keyPair) {
		return launchPeer(keyPair, 0);
	}

	/**
	 * Launch a pear for a given port number.
	 *
	 * @param keyPair KeyPair to use for the lauched peer.
	 *
	 * @param port Port number to use for the peer.
	 *
	 * @return Server object of the launched peer.
	 *
	 */
	public Server launchPeer(AKeyPair keyPair, int port) {
		return launchPeer(keyPair, 0, null);

	}

	/**
	 * Launch a peer.
	 *
	 * @param keyPair KeyPair to use for the lauched peer.
	 *
	 * @param port Port number to use for the peer.
	 *
	 * @param store Store to use for the peer.
	 *
	 * @return Server object of the launched peer.
	 *
	*/
	public Server launchPeer(AKeyPair keyPair, int port, AStore store) {
		Map<Keyword, Object> config = new HashMap<>();

		config.put(Keywords.PORT, null);
		if (port>0) {
			config.put(Keywords.PORT, port);
		}
		config.put(Keywords.KEYPAIR, keyPair);
		config.put(Keywords.STATE, Init.createState());

		// Use a different fresh store for each peer
		// config.put(Keywords.STORE, EtchStore.createTemp());

		if (store==null) {
			// Use a shared store
			store = Stores.getGlobalStore();
		}
		config.put(Keywords.STORE, store);

		Server peerServer = API.launchPeer(config, this);

		// add to list so we can remove from the session file when this app closes
		peerServerList.add(peerServer);

		return peerServer;
	}

	protected void waitForNetworkReady() {
		try {
			boolean isNetworkReady = false;
			long lastTimeStamp = 0;
			while (!isNetworkReady) {
				isNetworkReady = true;
				for (int index = 1; index < peerServerList.size(); index ++) {
					Server peerServer = peerServerList.get(index);
					convex.core.Peer peer = peerServer.getPeer();
					if ( peer.getTimeStamp() != lastTimeStamp) {
						lastTimeStamp = peer.getTimeStamp();
						isNetworkReady = false;
						break;
					}
				}
				Thread.sleep(1000);
			}
		} catch ( InterruptedException e) {
			return;
		}
	}
	/**
	 * Once the manager has launched 1 or more peers. The manager now needs too loop and wait for the peer(s)
	 * to exit.
	 *
	 * @param sessionFilename filename of the session file to save the peer session details.
	 *
	 */
	public void waitForPeers() {
		long consensusPoint = 0;
		long maxBlock = 0;

		loadSession();
		addAllToSession();
		storeSession();

		/*
			Go through each started peer server connection and make sure
			that each peer is connected to the other peer.
		*/
		/*
		for (Server peerServer: peerServerList) {
			connectToPeers(peerServer, session.getPeerAddressList());
		}
		*/

		// shutdown hook to remove/update the session file
		convex.api.Shutdown.addHook(Shutdown.CLI,new Runnable() {
		    public void run() {
				// System.out.println("peers stopping");
				// remove session file
				loadSession();
				removeAllFromSession();
				storeSession();
		    }
		});

		Server firstServer = peerServerList.get(0);
		System.out.println("Starting network Id: "+ firstServer.getNetworkId().toString());
		while (true) {
			try {
				ServerInformation serverInformation = serverEventQueue.take();
				int index = getServerIndex(serverInformation.getPeerKey());
				if (index >=0) {
					String item = toServerInformationText(serverInformation);
					System.out.println(String.format("#%d: %s", index + 1, item));
				}
			} catch (InterruptedException e) {
				System.out.println("Peer manager interrupted!");
				return;
			}
		}
	}

	protected String toServerInformationText(ServerInformation serverInformation) {
		String shortName = Utils.toFriendlyHexString(serverInformation.getPeerKey().toHexString()).replaceAll("^0x", "");
		String hostname = serverInformation.getHostname();
		String joined = "NJ";
		String synced = "NS";
		if (serverInformation.isJoined()) {
			joined = " J";
		}
		if (serverInformation.isSynced()) {
			synced = " S";
		}
		long blockCount = serverInformation.getBlockCount();
		int connectionCount = serverInformation.getConnectionCount();
		int trustedConnectionCount = serverInformation.getTrustedConnectionCount();
		long consensusPoint = serverInformation.getConsensusPoint();
		String item = String.format("Peer:%s URL: %s Status:%s %s Connections:%2d/%2d Level:%4d Block:%4d",
				shortName,
				hostname,
				joined,
				synced,
				connectionCount,
				trustedConnectionCount,
				consensusPoint,
				blockCount
		);

		return item;
	}

	protected int getServerIndex(AccountKey peerKey) {
		for (int index = 0; index < peerServerList.size(); index ++) {
			Server server = peerServerList.get(index);
			if (server.getPeer().getPeerKey().equals(peerKey)) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * Implements for IServerEvent
	 *
	 */
	public void onServerMessage(Server server, String message) {
		String shortName = Utils.toFriendlyHexString(server.getPeer().getPeerKey().toHexString());
		// System.out.println(shortName + ": " + message);
	}

	public void onServerChange(ServerInformation serverInformation) {
		try {
			serverEventQueue.put(serverInformation);
		} catch (InterruptedException e) {
		}
	}
}
