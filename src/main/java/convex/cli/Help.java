package convex.cli;

import java.util.List;

public class Help {

	public static void buildHelp(StringBuilder sb, List<String> commands) {
		if (commands.size()==0) {
			sb.append("Usage: convex [OPTIONS] COMMAND ... \n");
			sb.append('\n');
			
			sb.append("Commands:\n");
			sb.append("    peer                   "+"Operate a local peer.");
			sb.append("    transact               "+"Executes a transaction on the network via the current peer.");
			sb.append("    query                  "+"Executes a query on the current peer.");
			sb.append("    status                 "+"Reports on the current status of the network.");
			sb.append('\n');
			
			sb.append("Options:\n");
			sb.append("    -h, --help             "+"Display help for the given command.");
			sb.append("    -s, --server URL       "+"Specifies a peer server to use as current peer. Overrides configured peer if any.");
			sb.append("    -c, --config FILE      "+"Use the specified config file. Defaults to ~/.convex/config");
			sb.append("    -a, --address ADDRESS  "+"Use the specified user account address. Overrides configured Address if any.");
		} else {
			String cmd=commands.get(0);
			switch (cmd) {
			case "peer": {
				break;
			}
			
			default: sb.append("Command not known: "+cmd+"\n");
			}
		}
	}

}
