package convex.cli.etch;

import java.io.File;
import java.io.IOException;

import convex.cli.ATopCommand;
import convex.cli.CLIError;
import convex.core.util.Utils;
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
		if (etchStoreFilename==null) {
			throw new CLIError("No Etch store file specified. Maybe include --etch option or set environment variable CONVEX_ETCH_FILE ?");
		}
		
		File etchFile=Utils.getPath(etchStoreFilename);
		
		EtchStore store;
		try {
			store = EtchStore.create(etchFile);
			return store;
		} catch (IOException e) {
			throw new CLIError("Unable to load Etch store at: "+etchFile+ " cause: "+e.getMessage());
		}
	}


}

