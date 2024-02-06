package convex.cli.etch;

import convex.cli.ATopCommand;
import etch.EtchStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 *
 * Convex key sub commands
 *
 *		convex.key
 *
 */
@Command(name="etch",
	subcommands = {
		EtchDump.class,
		EtchInfo.class,
		EtchRead.class,
		EtchValidate.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=false,
	description="Manage etch database.")
public class Etch extends ATopCommand {

	@Option(names={"-e", "--etch"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_ETCH_FILE:-~/.convex/etch.db}",
			description="Convex Etch database filename. Will default to CONVEX_ETCH_FILE or ~/.convex/etch.db")
	String etchStoreFilename;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Etch(), System.out);
	}

	public EtchStore store() {
		return cli().getEtchStore(etchStoreFilename);
	}

}

