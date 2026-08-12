package convex.cli.etch;

import java.io.File;
import java.io.IOException;

import convex.cli.CLIError;
import convex.core.text.Text;
import convex.etch.EtchCorruptionError;
import convex.etch.EtchStrictValidator;
import convex.etch.EtchStrictValidator.Problem;
import convex.etch.EtchStrictValidator.Report;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="validate",
		mixinStandardHelpOptions=true,
		description="Strictly validates an Etch store offline")
public class EtchValidate extends AEtchCommand {

	@Option(names={"-m", "--max-failures"},
			description="Maximum number of failure details to print (all failures are still counted).")
	private Long maxFailures;

	@Override
	public void execute() {
		File file=etchMixin.getEtchFile();
		int detailLimit=100;
		if (maxFailures!=null) {
			if ((maxFailures<0)||(maxFailures>Integer.MAX_VALUE)) {
				throw new CLIError("Invalid maximum failure detail count: "+maxFailures);
			}
			detailLimit=maxFailures.intValue();
		}

		try {
			Report report=EtchStrictValidator.validate(file,sourceConfig(),
					new EtchStrictValidator.Options(detailLimit));
			for (Problem problem:report.problems()) {
				String location=(problem.position()<0)?"":" at "+problem.position();
				cli().inform(problem.kind()+location+": "+problem.message());
			}

			cli().println("Etch strict validation completed with "
					+report.failureCount()+" error(s)");
			cli().println("Index blocks:             "+Text.toFriendlyNumber(report.indexBlocks()));
			cli().println("Index pointers:           "+Text.toFriendlyNumber(report.indexPointers()));
			cli().println("Cells:                    "+Text.toFriendlyNumber(report.records()));
			cli().println("Empty slots:              "+Text.toFriendlyNumber(report.emptySlots()));
			cli().println("Logical database size:    "+Text.toFriendlyNumber(report.logicalBytes()));
			if (!report.isValid()) {
				cli().println("Malformed entries:        "+Text.toFriendlyNumber(report.malformedEntries()));
				cli().println("Hash mismatches:          "+Text.toFriendlyNumber(report.hashMismatches()));
				cli().println("CAD3 failures:            "+Text.toFriendlyNumber(report.canonicalFailures()));
				cli().println("Missing root hashes:      "+Text.toFriendlyNumber(report.missingRootHashes()));
				cli().println("I/O failures:             "+Text.toFriendlyNumber(report.ioFailures()));
			}
			if (report.records()>0) {
				cli().println("Avg. Encoding Length:     "+Text.toFriendlyDecimal(
						((double)report.encodingBytes())/report.records()));
				cli().println("Storage per Cell (bytes): "+Text.toFriendlyDecimal(
						((double)report.logicalBytes())/report.records()));
			}
			if (!report.isValid()) throw new CLIError("Etch strict validation failed");
		} catch (EtchCorruptionError e) {
			throw new CLIError("Etch file corrupt: "+file,e);
		} catch (IOException e) {
			throw new CLIError("IO error validating Etch store: "+file,e);
		} finally {
			closeKeyContexts();
		}
	}
}
