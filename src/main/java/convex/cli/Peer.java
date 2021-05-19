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

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

	protected void addPeerServer(Server peerServer) {
		peerServerList.add(peerServer);
	}

	protected void launchPeers(int count, AKeyPair[] keyPairs) {
		peerServerList.clear();

		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = keyPairs[i];
			Server peerServer = launchPeer(keyPair);
		}

		/*
			Go through each started peer server connection and make sure
			that each peer is connected to the other peer.
		*/
		for (int i = 0; i < count; i++) {
			Server peerServer = peerServerList.get(i);
			for (int j = 0; j < count; j++) {
				if (i == j) continue;
				Server serverDestination = peerServerList.get(j);
				InetSocketAddress addr = serverDestination.getHostAddress();
				try {
					peerServer.connectToPeer(addr);
				} catch (IOException e) {
					System.out.println("Connect failed to: "+addr);
				}
			}
		}
	}

	protected void openSession() {
		Session session = new Session();
		File sessionFile = new File(mainParent.getSessionFilename());
		try {
			session.load(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot load the session control file");
		}
		for (Server peerServer: peerServerList) {
			InetSocketAddress peerHostAddress = peerServer.getHostAddress();
			EtchStore store = (EtchStore) peerServer.getStore();

			session.addPeer(
				peerServer.getAddress().toHexString(),
				peerHostAddress.getHostName(),
				peerHostAddress.getPort(),
				store.getFileName()
			);
		}
		try {
			Helpers.createPath(sessionFile);
			session.store(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot store the session control data");
		}
	}

	protected void closeSession() {
		Session session = new Session();
		File sessionFile = new File(mainParent.getSessionFilename());
		try {
			session.load(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot load the session control file");
		}

		for (Server peerServer: peerServerList) {
			session.removePeer(peerServer.getAddress().toHexString());
		}
		try {
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
		Map<Keyword, Object> config = new HashMap<>();

		config.put(Keywords.PORT, null);
		if (port>0) {
			config.put(Keywords.PORT, port);
		}
		config.put(Keywords.KEYPAIR, keyPair);
		config.put(Keywords.STATE, Init.STATE);

		// Use a different fresh store for each peer
		// config.put(Keywords.STORE, EtchStore.createTemp());

		// Or Use a shared store
		config.put(Keywords.STORE, Stores.getGlobalStore());

		log.info("launch peer: "+keyPair.getAccountKey().toHexString());

		Server peerServer = API.launchPeer(config);

		addPeerServer(peerServer);

		return peerServer;
	}

	protected void waitForPeers() {
		long consensusPoint = 0;
		long maxBlock = 0;

		// write the launched peer details to a session file
		openSession();

		// shutdown hook to remove/update the session file
		convex.api.Shutdown.addHook(Shutdown.CLI,new Runnable() {
		    public void run() {
				// System.out.println("peers stopping");
				// remove session file
				closeSession();
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
