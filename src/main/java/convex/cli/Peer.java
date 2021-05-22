package convex.cli;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;


import convex.api.Shutdown;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.crypto.AKeyPair;
import convex.core.store.AStore;
import convex.core.Init;
import convex.core.store.Stores;
import convex.core.Order;
import convex.core.State;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;


/**
*
* Convex peer sub commands
*
*/
@Command(name="peer",
	subcommands = {
		PeerLocal.class,
		PeerManager.class,
		PeerStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operates a local peer(s) or local convex network.")
public class Peer implements Runnable {

	private static final Logger log = Logger.getLogger(Peer.class.getName());

	static public List<Server> peerServerList = new ArrayList<Server>();

	protected Session session = new Session();

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

	protected void launchPeers(int count, AKeyPair[] keyPairs) {
		peerServerList.clear();

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs[i];
			Server peerServer = launchPeer(keyPair);
		}
	}

	protected void connectToPeers(Server peerServer, InetSocketAddress[] addressList) {
		InetSocketAddress peerAddress = peerServer.getHostAddress();
		for (int index = 0; index < addressList.length; index++) {
			InetSocketAddress address = addressList[index];
			if (peerAddress != address) {
				try {
					peerServer.connectToPeer(address);
				} catch (IOException e) {
					System.out.println("Connect failed to: "+address);
				}
			}
		}
	}

	protected void loadSession() {
		File sessionFile = new File(mainParent.getSessionFilename());
		try {
			session.load(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot load the session control file");
		}
	}

	protected void addToSession(Server peerServer) {
		InetSocketAddress peerHostAddress = peerServer.getHostAddress();
		EtchStore store = (EtchStore) peerServer.getStore();

		session.addPeer(
			peerServer.getAddress().toHexString(),
			peerHostAddress.getHostName(),
			peerHostAddress.getPort(),
			store.getFileName()
		);
	}

	protected void addAllToSession() {
		for (Server peerServer: peerServerList) {
			addToSession(peerServer);
		}
	}

	protected void removeAllFromSession() {
		for (Server peerServer: peerServerList) {
			session.removePeer(peerServer.getAddress().toHexString());
		}
	}

	protected void storeSession() {
		File sessionFile = new File(mainParent.getSessionFilename());
		try {
			Helpers.createPath(sessionFile);
			if (session.size() > 0) {
				session.store(sessionFile);
			}
			else {
				sessionFile.delete();
			}
		} catch (IOException e) {
			log.severe("Cannot store the session control data");
		}
	}

	protected Server launchPeer(AKeyPair keyPair) {
		return launchPeer(keyPair, 0);
	}

	protected Server launchPeer(AKeyPair keyPair, int port) {
		return launchPeer(keyPair, 0, null);

	}

	protected Server launchPeer(AKeyPair keyPair, int port, AStore store) {
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

		log.info("launch peer: "+keyPair.getAccountKey().toHexString());

		Server peerServer = API.launchPeer(config);

		peerServerList.add(peerServer);

		return peerServer;
	}

	protected void waitForPeers() {
		long consensusPoint = 0;
		long maxBlock = 0;

		// write the launched peer details to a session file
		loadSession();
		addAllToSession();
		storeSession();

		/*
			Go through each started peer server connection and make sure
			that each peer is connected to the other peer.
		*/
		for (Server peerServer: peerServerList) {
			connectToPeers(peerServer, session.getAddressList());
		}

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
		State lastState = firstServer.getPeer().getConsensusState();
		log.info("state hash: "+lastState.getHash());

		while (true) {
			try {
				Thread.sleep(30);
				for (Server peerServer: peerServerList) {
					convex.core.Peer peer = peerServer.getPeer();
					if (peer==null) continue;

					State state = peer.getConsensusState();
					// System.out.println("state " + state);
					Order order=peer.getPeerOrder();
					if (order==null) continue; // not an active peer?
					maxBlock = Math.max(maxBlock, order.getBlockCount());

					long peerConsensusPoint = peer.getConsensusPoint();
					if (peerConsensusPoint > consensusPoint) {
						consensusPoint = peerConsensusPoint;
						System.err.printf("Consenus State update detected at depth %d\n", consensusPoint);
					}
				}
			} catch (InterruptedException e) {
				System.out.println("Peer manager interrupted!");
				return;
			}
		}
	}
}
