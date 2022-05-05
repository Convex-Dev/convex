package convex.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 * Convex Transaction sub command
 *
 *		convex.transaction
 *
 */
@Command(name="transact",
	mixinStandardHelpOptions=true,
	description="Execute a user transaction on the network via a peer.")
public class Transaction implements Runnable {

	@ParentCommand
	protected Main mainParent;

	private static final Logger log = LoggerFactory.getLogger(Transaction.class);

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to use to run the transaction.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names={"--port"},
		description="Port number to connect or create a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Option(names={"-a", "--address"},
		description="Account address to use for the transaction request.")
	private long addressNumber;

	@Option(names={"-t", "--timeout"},
		description="Timeout in miliseconds.")
	private long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;

	@Parameters(paramLabel="transactionCommand",
		description="Transaction Command")
	private String[] transactionList;

	@Override
	public void run() {
		AKeyPair keyPair = null;
		keyPair = mainParent.loadKeyFromStore(keystorePublicKey);

		if (keyPair == null) {
			log.warn("cannot load a valid key pair to perform this transaction");
			return;
		}

		if (addressNumber == 0) {
			log.warn("--address. You need to provide a valid address number");
			return;
		}

		Address address = Address.create(addressNumber);

		Convex convex = null;
		try {
			convex = mainParent.connectToSessionPeer(hostname, port, address, keyPair);
			String transactionCommand = String.join(" ", transactionList);
			log.info("Executing transaction: '{}'\n", transactionCommand);
			ACell message = Reader.read(transactionCommand);
			ATransaction transaction = Invoke.create(address, -1, message);
			Result result = convex.transactSync(transaction, timeout);
			mainParent.printResult(result);
		} catch (Exception e) {
			throw new CLIError("Error executing transation",e);
		}
	}

}
