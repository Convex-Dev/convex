package convex.cli;

import java.util.List;

public class Key {

	static void buildKeyHelp(StringBuilder sb, List<String> commands) {
		String cmd=commands.get(0);
		
		sb.append("Usage: convex [OPTIONS] "+cmd+" COMMAND ... \n");
		sb.append('\n');
		sb.append("Commands:\n");
		sb.append(CLIUtils.buildTable(
				"gen",     "Generate a new private key pair."));
		sb.append('\n');		
		sb.append("Options:\n");
		sb.append(CLIUtils.buildTable(
				"-h, --help",           "Display help for the command '"+cmd+"'.",
				"-k, --keystore FILE",  "Use the specified keystore. Defaults to the keystore specified in config file, or '~/.convex/keystore otherwise'",
				"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
				"-p, --passphrase", "Specify a passphrase to protect key. If not specified, will be promtped."));
	}

}
