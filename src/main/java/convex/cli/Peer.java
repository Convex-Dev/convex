package convex.cli;

import java.util.HashMap;
import java.util.Map;

import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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

	@ParentCommand
	private Main parent;

	@Option(names={"-p", "--port"},
		description="Specify a port to run the local peer.")
	private int port;

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

		Server s = API.launchPeer(peerConfig);
		System.out.println("Peer started at "+s.getHostAddress() +" with public key "+s.getPeer().getPeerKey());
		while (true) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				System.out.println("Peer interrupted!");
				return;
			}
		}
	}

	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

}
