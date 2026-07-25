package convex.cli.peer;

import convex.cli.ATopCommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.data.Keyword;
import convex.restapi.RESTConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.IOException;
import java.util.HashMap;


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
		PeerList.class,
		PeerBackup.class,
		PeerGenesis.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Operate a Convex Peer")
public class Peer extends ATopCommand {

	@Option(names={ "-c", "--config"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_PEER_CONFIG}",
			description="Peer configuration file (JSON5). Can specify with CONVEX_PEER_CONFIG. Explicit command line options take precedence over config file values.")
	private String configFilename;

	/**
	 * Loads the specified peer config file into launch config entries.
	 * @return Launch config map (empty if no config file specified)
	 */
	public HashMap<Keyword,Object> loadPeerConfig() {
		HashMap<Keyword,Object> result=new HashMap<>();
		if ((configFilename==null)||configFilename.isBlank()) return result;
		try {
			RESTConfig pc=RESTConfig.load(configFilename.trim());
			result.putAll(pc.toLegacy());
			inform("Loaded peer config from: "+configFilename);
		} catch (IOException e) {
			throw new CLIError(ExitCodes.CONFIG,"Unable to read peer config file: "+configFilename,e);
		} catch (RuntimeException e) {
			throw new CLIError(ExitCodes.CONFIG,"Unable to parse peer config file: "+configFilename+" ("+e.getMessage()+")",e);
		}
		return result;
	}

	@Override
	public void execute() {
		showUsage();
	}



}
