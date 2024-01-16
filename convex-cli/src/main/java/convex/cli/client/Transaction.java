package convex.cli.client;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
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
public class Transaction extends AClientCommand {

	protected static final Logger log = LoggerFactory.getLogger(Transaction.class);

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to sign the transaction.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Parameters(paramLabel="transactionCommand",
		description="Transaction Command")
	private String transactionCode;

	@Override
	public void run() {
		try {
			Convex convex = connect();
			Address address=convex.getAddress();
			AKeyPair keyPair = convex.getKeyPair();
			
			// If we don't already have keypair specified, attempt to find
			// correct key pair for address from the network
			if (keyPair==null) {
				Result ar=convex.query("*key*").get(1000,TimeUnit.MILLISECONDS);
				if (ar.isError()) throw new CLIError("Unable to get *key* for Address "+address+" : "+ar);
				ACell v=ar.getValue();
				if (v instanceof ABlob) {
					String pk=((ABlob)v).toHexString();
					keyPair=mainParent.loadKeyFromStore(pk);
					if (keyPair==null) {
						// We didn't find required keypair
						throw new CLIError("Unable to get keypair "+pk+" for Address "+address+" : "+ar);
					}
					convex.setKeyPair(keyPair);
				}
			}

			log.debug("Executing transaction: '{}'\n", transactionCode);
			ACell message = Reader.read(transactionCode);
			ATransaction transaction = Invoke.create(address, -1, message);
			Result result = convex.transactSync(transaction, timeout);
			mainParent.printResult(result);
		} catch (Exception e) {
			throw new CLIError("Error executing transation",e);
		}
	}

}
