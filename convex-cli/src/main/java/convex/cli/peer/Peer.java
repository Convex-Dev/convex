package convex.cli.peer;

import convex.cli.ATopCommand;
import convex.cli.Constants;
import convex.cli.Main;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;


/**
 *
 * Convex peer sub commands
 *
 *		convex.peer
 *
 */
@Command(name="peer",
	subcommands = {
		PeerCreate.class,
		PeerStart.class,
		PeerGenesis.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operate a Convex Peer")
public class Peer extends ATopCommand {
	
	@Option(names={ "-c", "--config"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_PEER_CONFIG}",
			description="Use the specified config file. If not specified, will default to CONVEX_PEER_CONFIG or "+Constants.CONFIG_FILENAME)
	private String configFilename;


	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		showUsage();
	}



}
