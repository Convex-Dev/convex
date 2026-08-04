package convex.cli.etch;

import convex.cli.CLIError;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to reconstruct a damaged Etch store into a fresh destination.
 *
 * The implementation will use unsafe read-only maintenance access to scan the
 * source through physical EOF, copy independently validated CAD3 cells and set
 * the selected source root only after verifying it is fully persisted in the
 * destination. The source will never be modified.
 */
@Command(name="repair",
		mixinStandardHelpOptions=true,
		description="Repairs a possibly corrupt Etch store into a fresh file by scanning for "
				+ "valid content-addressed cells. The source is not modified.")
public class EtchRepair extends AEtchCommand {

	@Option(names={"--into"},
			required=true,
			description="Fresh destination Etch store file. Must not already contain data.")
	private String destFilename;

	@Override
	public void execute() {
		throw new CLIError("Etch repair is not yet implemented (destination: "+destFilename+")");
	}
}
