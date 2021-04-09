package convex.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.peer.API;
import convex.peer.Server;

public class Peer {

	public static void buildPeerHelp(StringBuilder sb, List<String> commands) {
		String cmd=commands.get(0);

		sb.append("Usage: convex [OPTIONS] "+cmd+" COMMAND ... \n");
		sb.append('\n');
		sb.append("Commands:\n");
		sb.append(CLIUtils.buildTable(
				"start",     "Start a peer server."));
		sb.append('\n');		
		sb.append("Options:\n");
		sb.append(CLIUtils.buildTable(
				"-h, --help",           "Display help for the command '"+cmd+"'.",
				"-k, --keystore FILE",  "Use the specified keystore. Defaults to the keystore specified in config file, or '~/.convex/keystore otherwise'",
				"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
				"--port", "Specify a port to run the peer."));

	}

	public static int runPeer(List<String> commands, Properties config) {
		if (commands.size()<=1) {
			return Help.runHelp(commands);
		}
		
		String cmd=commands.get(1);
		if ("start".equals(cmd)) {
			return runStart(config);
		} else {
			System.out.println("Unrecognised key command: "+cmd);
			return 1;
		}
	}

	private static int runStart(Properties config) {
		System.out.println("Starting peer...");
		Map<Keyword,Object> pc=new HashMap<>();
		
		String port=config.getProperty("port");
		if (port!=null) {
			pc.put(Keywords.PORT, Integer.parseInt(port));
		}
		
		Server s = API.launchPeer(pc);
		System.out.println("Peer started at "+s.getHostAddress() +" with public key "+s.getPeer().getPeerKey());
		while (true) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				System.out.println("Peer interrupted!");
				return 1;
			}
		}
	}

}
