package convex.cli.peer;

import convex.cli.ATopCommand;
import convex.cli.Main;
import etch.EtchStore;
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
			description="Use the specified config file. If not specified, will check ~/.convex/convex.config")
	private String configFilename;

	@Option(names={"-e", "--etch"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_ETCH_FILE:-~/.convex/etch.db}",
			description="Convex Etch database filename. Will default to CONVEX_ETCH_FILE or ~/.convex/etch.db")
	String etchStoreFilename;

	// private static final Logger log = Logger.getLogger(Peer.class.getName());

	@ParentCommand
	protected Main mainParent;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Peer(), System.out);
	}

	public EtchStore getEtchStore() {
		return cli().getEtchStore(etchStoreFilename);
	}

}
