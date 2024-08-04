package convex.cli.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
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

	@Option(names={"--public-key"},
		description="Hex prefix of the public key in the Keystore to sign the transaction.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Parameters(paramLabel="transactionCommand",
		description="Transaction Command")
	private String transactionCode;

	@Override
	public void run() {
		try {
			Convex convex = clientConnect();
			if (!ensureAddress(convex)) {	
				throw new CLIError("Must specify a valid address for transaction.");
			}
			
			if (!ensureKeyPair(convex)) {	
				throw new CLIError("Must provide a key pair to sign transaction.");
			}

			Address address=convex.getAddress();

			log.trace("Executing transaction: '{}'\n", transactionCode);
			ACell message = Reader.read(transactionCode);
			ATransaction transaction = Invoke.create(address, ATransaction.UNKNOWN_SEQUENCE, message);
			Result result = convex.transactSync(transaction, timeout);
			mainParent.printResult(result);
		} catch (CLIError e) {
			throw e;
		} catch (Exception e) {
			// General catch all
			throw new CLIError("Error executing transation",e);
		}
	}


}
