package convex.cli.peer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import convex.api.Convex;
import convex.core.util.Shutdown;
import convex.cli.Helpers;
import convex.core.Belief;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.SignedData;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.IServerEvent;
import convex.peer.Server;
import convex.peer.ServerEvent;
import convex.peer.ServerInformation;
import etch.EtchStore;


/**
*
* Convex CLI PeerManager
*
*/

public class PeerManager implements IServerEvent {

	private static final Logger log = LoggerFactory.getLogger(PeerManager.class.getName());

	private static final long TRANSACTION_TIMEOUT_MILLIS = 50000;
	private static final int FRIENDLY_HEX_STRING_SIZE = 6;

	protected List<Server> peerServerList = new ArrayList<Server>();

	protected Session session = new Session();

	protected String sessionFilename;

	protected AKeyPair keyPair;

	protected Address address;

	protected AStore store;

	protected BlockingQueue<ServerEvent> serverEventQueue = new ArrayBlockingQueue<ServerEvent>(1024);


	private PeerManager(String sessionFilename, AKeyPair keyPair, Address address, AStore store) {
        this.sessionFilename = sessionFilename;
		this.keyPair = keyPair;
		this.address = address;
		this.store = store;
	}

	public static PeerManager create(String sessionFilename) {
        return new PeerManager(sessionFilename, null, null, null);
	}

	public static PeerManager create(String sessionFilename, AKeyPair keyPair, Address address, AStore store) {
        return new PeerManager(sessionFilename, keyPair, address, store);
	}

	public void launchLocalPeers(List<AKeyPair> keyPairList, int peerPorts[]) {
		List<AccountKey> keyList=keyPairList.stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());

		State genesisState=Init.createState(keyList);
		peerServerList = API.launchLocalPeers(keyPairList,genesisState, peerPorts, this);
	}

	public List<Hash> getNetworkHashList(String remotePeerHostname) {
		InetSocketAddress remotePeerAddress = Utils.toInetSocketAddress(remotePeerHostname);
		int retryCount = 5;
		Convex convex = null;
		Result result = null;
		while (retryCount > 0) {
			try {
				convex = Convex.connect(remotePeerAddress, address, keyPair);
				Future<Result> cf =  convex.requestStatus();
				result = cf.get(TRANSACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
				retryCount = 0;
			} catch (IOException | InterruptedException | ExecutionException | TimeoutException e ) {
				// raiseServerMessage("unable to connect to remote peer at " + remoteHostname + ". Retrying " + e);
				retryCount --;
			}
		}
		if ((convex==null)||(result == null)) {
			throw new Error("Failed to join network: Cannot connect to remote peer at "+remotePeerHostname);
		}
		convex.close();
		List<Hash> hashList = new ArrayList<Hash>(5);
		AVector<ACell> values = result.getValue();
		hashList.add(RT.ensureHash(values.get(0)));		// beliefHash
		hashList.add(RT.ensureHash(values.get(1)));		// stateHash
		hashList.add(RT.ensureHash(values.get(2)));		// netwokIdHash
		hashList.add(RT.ensureHash(values.get(4)));		// consensusHash

		return hashList;
	}

	public State aquireState(String remotePeerHostname, Hash stateHash) {
		InetSocketAddress remotePeerAddress = Utils.toInetSocketAddress(remotePeerHostname);
		Convex convex = null;
		State state = null;
		try {
			convex = Convex.connect(remotePeerAddress, address, keyPair);
			Future<State> bf = convex.acquire(stateHash, store);
			state = bf.get(TRANSACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			convex.close();
		} catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
			throw new Error("cannot aquire network state: " + e);
		}
		return state;

	}
	public SignedData<Belief> aquireBelief(String remotePeerHostname, Hash beliefHash) {
		// sync the etch db with the network state
		Convex convex = null;
		SignedData<Belief> signedBelief = null;
		InetSocketAddress remotePeerAddress = Utils.toInetSocketAddress(remotePeerHostname);
		try {
			convex = Convex.connect(remotePeerAddress, address, keyPair);
			Future<SignedData<Belief>> cf = convex.acquire(beliefHash, store);
			signedBelief = cf.get(TRANSACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			convex.close();
		} catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
			throw new Error("cannot acquire belief: " + e);
		}

		return signedBelief;
	}

    public void launchPeer(
		String hostname,
		int port,
		String remotePeerHostname
	) {
		Map<Keyword, Object> config = new HashMap<>();
		if (port > 0 ) {
			config.put(Keywords.PORT, port);
		}
		config.put(Keywords.STORE, store);
		config.put(Keywords.SOURCE, remotePeerHostname);
		config.put(Keywords.KEYPAIR, keyPair);
		config.put(Keywords.EVENT_HOOK, this); // Add this as IServerEvent hook
		Server server = API.launchPeer(config);

		peerServerList.add(server);
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
			log.error("Cannot load the session control file");
		}
	}

	/**
	 * Add a peer to the session list of peers.
	 *
	 * @param peerServer Add the peerServer to the list of peers for this session.
	 *
	 */
	protected void addToSession(Server peerServer) {
		EtchStore store = (EtchStore) peerServer.getStore();

		session.addPeer(
			peerServer.getPeerKey(),
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
			session.removePeer(peerServer.getPeerKey());
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
			if (session.getSize() > 0) {
				session.store(sessionFile);
			}
			else {
				sessionFile.delete();
			}
		} catch (IOException e) {
			log.error("Cannot store the session control data");
		}
	}

	/**
	 * Once the manager has launched 1 or more peers. The manager now needs too loop and show any events generated by the peers
	 *
	 */
	public void showPeerEvents() {

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
		Shutdown.addHook(Shutdown.CLI,new Runnable() {
		    public void run() {
				// System.out.println("peers stopping");
				// remove session file
				loadSession();
				removeAllFromSession();
				storeSession();
		    }
		});

		Server firstServer = peerServerList.get(0);
		System.out.println("Starting network Id: "+ firstServer.getPeer().getNetworkID().toString());
		while (true) {
			try {
				ServerEvent event = serverEventQueue.take();
                ServerInformation information = event.getInformation();
				int index = getServerIndex(information.getPeerKey());
				if (index >=0) {
					String item = toServerInformationText(information);
					System.out.println(String.format("#%d: %s Msg: %s", index + 1, item, event.getReason()));
				}
			} catch (InterruptedException e) {
				System.out.println("Peer manager interrupted!");
				return;
			}
		}
	}

	protected String toServerInformationText(ServerInformation serverInformation) {
		String shortName = Utils.toFriendlyHexString(serverInformation.getPeerKey().toHexString(), FRIENDLY_HEX_STRING_SIZE);
		String hostname = serverInformation.getHostname();
		String joined = "NJ";
		String synced = "NS";
		if (serverInformation.isJoined()) {
			joined = " J";
		}
		if (serverInformation.isSynced()) {
			synced = " S";
		}
		String stateHash =  Utils.toFriendlyHexString(serverInformation.getStateHash().toHexString(), FRIENDLY_HEX_STRING_SIZE);
		String beliefHash =  Utils.toFriendlyHexString(serverInformation.getBeliefHash().toHexString(), FRIENDLY_HEX_STRING_SIZE);
		int connectionCount = serverInformation.getConnectionCount();
		int trustedConnectionCount = serverInformation.getTrustedConnectionCount();
		long consensusPoint = serverInformation.getConsensusPoint();
		String item = String.format("Peer:%s URL: %s Status:%s %s Connections:%2d/%2d Consensus:%4d State:%s Belief:%s",
				shortName,
				hostname,
				joined,
				synced,
				connectionCount,
				trustedConnectionCount,
				consensusPoint,
				stateHash,
				beliefHash
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

	public void onServerChange(ServerEvent serverEvent) {
		// add in queue if space available
		serverEventQueue.offer(serverEvent);
	}
}
