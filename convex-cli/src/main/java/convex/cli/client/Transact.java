package convex.cli.client;

import java.io.IOException;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.cvm.Address;
import convex.core.data.Format;
import convex.core.lang.Reader;
import convex.core.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 *
 * Convex Transaction sub command
 *
 *		convex.transaction
 *
 */
@Command(name="transact",
	mixinStandardHelpOptions=true,
	description="Execute a user transaction on the Convex network.")
public class Transact extends AClientCommand {

	protected static final Logger log = LoggerFactory.getLogger(Transact.class);
	
	@Option(names={"-o", "--output-file"},
			description="Output file for the transaction instead of execution. Use '-' for STDOUT.")
	private String outputFilename;

	@Parameters(paramLabel="transactionCommand",
		description="Transaction Command")
	private String transactionCode;

	@Override
	public void execute() throws InterruptedException {
		Address address=getUserAddress();
		if (address==null) throw new CLIError(ExitCodes.USAGE,"You must specify a valid origin address for the transaction.");

		log.trace("Executing transaction: '{}'\n", transactionCode);

		ACell message = Reader.read(transactionCode);
		ATransaction transaction = Invoke.create(address, ATransaction.UNKNOWN_SEQUENCE, message);

		if (outputFilename==null) {
			try (Convex convex = connectQuery()) {
				ensureKeyPair(convex);
				Result result = convex.transactSync(transaction);
				mainParent.printResult(result);
				if (result.isError()) {
					throw new CLIError("Transaction failed with error code: "+result.getErrorCode());
				}
			}
		} else {
			// Offline mode: encode the transaction to a file / STDOUT, no network connection needed
			String output=Format.encodeMultiCell(transaction,true).toHexString();
			if ("-".equals(outputFilename.trim())) {
				println(output);
			} else {
				try {
					FileUtils.writeFileAsString(Paths.get(outputFilename),output);
				} catch (IOException e) {
					throw new CLIError("Failed to write output file: "+outputFilename, e);
				}
			}
		}
	}
}
