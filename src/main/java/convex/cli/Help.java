package convex.cli;

import java.util.List;

public class Help {

	public static void buildHelp(StringBuilder sb, List<String> commands) {
		if (commands.size()==0) {
			sb.append("Usage: convex [OPTIONS] COMMAND ... \n");
			sb.append('\n');
			
			sb.append("Commands:\n");
			sb.append(CLIUtils.buildTable(
					"key",     "Manage local Convex key store.",
					"peer",    "Operate a local peer.",
					"transact","Execute a transaction on the network via the current peer.",
					"query",   "Execute a query on the current peer.",
					"status",  "Reports on the current status of the network."));
			sb.append('\n');		
			sb.append("Options:\n");
			sb.append(CLIUtils.buildTable(
					"-h, --help",           "Display help for the given command.",
					"-s, --server URL",     "Specifies a peer server to use as current peer. Overrides configured peer if any.",
					"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
					"-a, --address ADDRESS","Use the specified user account address. Overrides configured Address if any."));
		} else {
			String cmd=commands.get(0);
			switch (cmd) {
			case "peer": {
				Peer.buildPeerHelp(sb,commands);
				break;
			}
			case "key": {
				Key.buildKeyHelp(sb, commands);
				break;
			}
			case "transact": {
				CLIClient.buildTransactHelp(sb, commands);
				break;
			}
			case "query": {
				CLIClient.buildQueryHelp(sb, commands);
				break;
			}
			
			
			default: sb.append("Command not known: "+cmd+"\n");
			}
		}
	}

	static int runHelp(List<String> argList) {
		StringBuilder sb= new StringBuilder();
		buildHelp(sb,argList);
		System.out.println(sb.toString());
		return 0;
	}

}
