package convex.cli;

import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.Init;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account fund command
 *
 *  convex.account.fund
 *
 */

@Command(name="fund",
	mixinStandardHelpOptions=true,
	description="Transfers funds to account using a public/private key from the keystore.%n"
		+ "You must provide a valid keystore password to the keystore and a valid address.%n"
		+ "If the keystore is not at the default location also the keystore filename.")
public class AccountFund implements Runnable {

	private static final Logger log = Logger.getLogger(AccountFund.class.getName());

	@ParentCommand
	private Account accountParent;

	@Option(names={"-i", "--index"},
		defaultValue="-1",
		description="Keystore index of the public/private key to use to create an account.")
	private int keystoreIndex;

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to use to create an account.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names={"--port"},
		description="Port number to connect to a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Option(names={"-a", "--address"},
		description="Account address to use to request funds.")
	private long addressNumber;


	@Parameters(paramLabel="amount",
		defaultValue=""+Constants.ACCOUNT_FUND_AMOUNT,
		description="Amount to fund the account")
	private long amount;

	@Override
	public void run() {

		Main mainParent = accountParent.mainParent;

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

		Convex convex = null;
		Address address = Address.create(addressNumber);
		try {
			convex = mainParent.connectToSessionPeer(hostname, port, Init.HERO, Init.HERO_KP);
			convex.transferSync(address, amount);
			convex = mainParent.connectToSessionPeer(hostname, port, address, keyPair);
			Long balance = convex.getBalance(address);
			log.info("account balance: " + balance);
		} catch (Throwable t) {
			log.severe(t.getMessage());
			t.printStackTrace();
			return;
		}

	}
}
