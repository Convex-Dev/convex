package convex.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;


import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.crypto.AKeyPair;
import convex.core.Init;
import convex.core.Order;
import convex.core.State;
import convex.core.store.Stores;
import convex.peer.API;
import convex.peer.Server;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex peer sub commands
*
*/
@Command(name="peer",
	mixinStandardHelpOptions=true,
	description="Operates a local peer.")
public class Peer implements Runnable {

	private static final Logger log = Logger.getLogger(Peer.class.getName());

	static public List<Server> peerServerList = new ArrayList<Server>();

	@ParentCommand
	private Main parent;

	@Option(names={"-p", "--port"},
		description="Specify a port to run the local peer.")
	private int port;

	@Option(names={"--count"},
		defaultValue = ""+Init.NUM_PEERS,
		description="Peer count, number of peers to start. Default: ${DEFAULT-VALUE}")
	private int count;


	// peer start command
	@Command(name="start",
		mixinStandardHelpOptions=true,
		description="Starts a peer server.")
	void start() {
		System.out.println("Starting peer...");

		// Parse peer config
		Map<Keyword,Object> peerConfig=new HashMap<>();

		if (port!=0) {
			peerConfig.put(Keywords.PORT, port);
		}

		long consensusPoint = 0;
		long maxBlock = 0;
		log.info("Starting "+count+" peers");
		launchAllPeers(count);
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
						// System.out.printf("Consensus state %s\n", consensusPoint);
					}
				}
			} catch (InterruptedException e) {
				System.out.println("Peer manager interrupted!");
				return;
			}
		}
	}

	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

	protected void addPeerServer(Server peerServer) {
		peerServerList.add(peerServer);
	}

	protected void launchAllPeers(int count) {
		peerServerList.clear();

		for (int i = 0; i < count; i++) {
			launchPeer(Init.KEYPAIRS[i]);
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

	protected Server launchPeer(AKeyPair keyPair) {
		Map<Keyword, Object> config = new HashMap<>();

		config.put(Keywords.PORT, null);
		config.put(Keywords.KEYPAIR, keyPair);
		config.put(Keywords.STATE, Init.STATE);

		// Use a different fresh store for each peer
		// config.put(Keywords.STORE, EtchStore.createTemp());

		// Or Use a shared store
		config.put(Keywords.STORE, Stores.getGlobalStore());

		Server peerServer = API.launchPeer(config);

		addPeerServer(peerServer);
		return peerServer;
	}

}
