package convex.cli.etch;

import java.io.File;
import java.io.IOException;

import convex.cli.CLIError;
import convex.core.text.Text;
import convex.core.util.FileUtils;
import convex.etch.EtchRebuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to reconstruct a damaged Etch store into a fresh destination.
 *
 * Uses exclusive read-only maintenance access to scan the source through
 * physical EOF, copy independently validated CAD3 cells and set the selected
 * source root only after verifying it is fully persisted in the destination.
 * The source is never modified.
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
		File source=etchMixin.getEtchFile();
		File destination=FileUtils.getFile(destFilename);
		try {
			EtchRebuilder.Result result=EtchRebuilder.rebuildConfigured(
					source,sourceConfig(),destination,
					sourcePolicy->destinationConfig(sourcePolicy,true));
			println("Etch repair status:      "+result.status());
			println("Selected source root:    "+result.sourceRoot());
			println("Indexed records accepted: "+Text.toFriendlyNumber(result.indexedRecordsAccepted()));
			println("Scanned records accepted: "+Text.toFriendlyNumber(result.scannedRecordsAccepted()));
			println("Destination values:      "+Text.toFriendlyNumber(result.destinationValues()));
			println("Candidate bytes scanned: "+Text.toFriendlyNumber(result.bytesScanned()));
			println("Index problems:          "+Text.toFriendlyNumber(result.indexProblems()));
			println("Destination:             "+result.destination().getCanonicalPath());
			for (String problem:result.problems()) informWarning(problem);

			if (result.status()!=EtchRebuilder.Status.COMPLETE) {
				String detail=result.isRootComplete()
						?"physical scan did not reach EOF"
						:"missing root hashes: "+result.missingRootHashes().size();
				throw new CLIError("Etch repair produced a valid partial destination ("
						+result.status()+"); "+detail);
			}
		} catch (IOException e) {
			throw new CLIError("Etch repair failed: "+e.getMessage(),e);
		} finally {
			closeKeyContexts();
		}
	}
}
