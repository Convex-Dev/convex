package convex.cli;

import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.Result;

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
@Command(name="transaction",
	aliases={"transact", "tr"},
	mixinStandardHelpOptions=true,
	description="Execute a transaction on the network via a peer.")
public class Transaction implements Runnable {

	@ParentCommand
	protected Main mainParent;

	private static final Logger log = Logger.getLogger(Transaction.class.getName());


	@Option(names={"-i", "--index"},
		defaultValue="-1",
		description="Keystore index of the public/private key to use to run the transaction.")
	private int keystoreIndex;

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


	@Parameters(paramLabel="transactionCommand",
		description="Transaction Command")
	private String transactionCommand;

	@Override
	public void run() {

		AKeyPair keyPair = null;
		try {
			keyPair = mainParent.loadKeyFromStore(keystorePublicKey, keystoreIndex);
		} catch (Error e) {
			log.info(e.getMessage());
			return;
		}

		if (addressNumber == 0) {
			log.severe("--address. You need to provide a valid address number");
			return;
		}

		Address address = Address.create(addressNumber);

		Convex convex = null;
		try {
			convex = mainParent.connectToSessionPeer(hostname, port, address, keyPair);
			System.out.printf("Executing transaction: %s\n", transactionCommand);
			ACell message = Reader.read(transactionCommand);
			ATransaction transaction = Invoke.create(address, -1, message);

			Result result = convex.transactSync(transaction, 5000);
			System.out.println(result);
		} catch (Throwable t) {
			log.severe(t.getMessage());
			t.printStackTrace();
			return;
		}
	}

}
