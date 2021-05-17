package convex.cli;

import java.lang.Math;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;

import convex.api.Shutdown;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.Init;
import convex.core.Order;
import convex.core.State;
import convex.peer.Server;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/*
	*  peer start command
	*
*/

@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts a peer server.")
public class PeerStart implements Runnable {

	private static final Logger log = Logger.getLogger(PeerStart.class.getName());

	@ParentCommand
	private Peer peerParent;

	@Parameters(paramLabel="count",
		defaultValue = ""+Init.NUM_PEERS,
		description="Number of peers to start. Default: ${DEFAULT-VALUE}")
	private int count;

	@Override
	public void run() {
		System.out.println("Starting peer...");

		// Parse peer config
		Map<Keyword,Object> peerConfig=new HashMap<>();

		if (peerParent.port!=0) {
			peerConfig.put(Keywords.PORT, Math.abs(peerParent.port));
		}

		long consensusPoint = 0;
		long maxBlock = 0;
		log.info("Starting "+count+" peers");
		peerParent.launchAllPeers(count);

		// shutdown hook to remove/update the session file
		convex.api.Shutdown.addHook(Shutdown.CLI,new Runnable() {
		    public void run() {
				System.out.println("peers stopping");
				// remove session file
		    }
		});

		while (true) {
			try {
				Thread.sleep(30);
				for (Server peerServer: peerParent.peerServerList) {
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
}
