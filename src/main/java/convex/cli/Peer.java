package convex.cli;

import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;


import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.crypto.AKeyPair;
import convex.core.Init;
import convex.core.store.Stores;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;
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
	subcommands = {
		PeerStart.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operates a local peer.")
public class Peer implements Runnable {

	private static final Logger log = Logger.getLogger(Peer.class.getName());

	static public List<Server> peerServerList = new ArrayList<Server>();

	@ParentCommand
	protected Main mainParent;

	@Option(names={"-p", "--port"},
		description="Specify a port to run the local peer.")
	protected int port;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

	protected void addPeerServer(Server peerServer) {
		peerServerList.add(peerServer);
	}

	protected void launchAllPeers(int count) {
		peerServerList.clear();

		Session session = new Session();
		File sessionFile = new File(mainParent.getSessionFilename());
		/*
		 *	Currently we do not read the session on start, for add we should read this
		 *
		try {
			session.load(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot load the session control file");
		}
		*/
		for (int i = 0; i < count; i++) {
			AKeyPair keyPair = Init.KEYPAIRS[i];
			Server peerServer = launchPeer(keyPair);
			InetSocketAddress peerHostAddress = peerServer.getHostAddress();
			System.out.println("Peer address: " + peerHostAddress.getAddress() + " port: " + peerHostAddress.getPort());
			EtchStore store = (EtchStore) peerServer.getStore();
			System.out.println("Peer store name " + store.getFileName());

			session.addPeer(peerHostAddress, store.getFileName());
		}

		try {
			Helpers.createPath(sessionFile);
			session.store(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot store the session control data");
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
