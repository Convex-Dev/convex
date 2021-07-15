package convex.cli;

import java.util.List;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.util.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account create command
 *
 *  convex.account.create
 *
 */

@Command(name="create",
	aliases={"cr"},
	mixinStandardHelpOptions=true,
	description="Creates an account using a public/private key from the keystore.%n"
		+ "You must provide a valid keystore password to the keystore.%n"
		+ "If the keystore is not at the default location also the keystore filename.")
public class AccountCreate implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(AccountCreate.class);

	@ParentCommand
	private Account accountParent;

	@Option(names={"-i", "--index-key"},
		defaultValue="0",
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

	@Option(names={"-f", "--fund"},
		description="Fund the account with the default fund amount.")
	private boolean isFund;

	@Override
	public void run() {

		Main mainParent = accountParent.mainParent;

		AKeyPair keyPair = null;

		if (keystoreIndex > 0 || keystorePublicKey != null) {
			try {
				keyPair = mainParent.loadKeyFromStore(keystorePublicKey, keystoreIndex);
			} catch (Error e) {
				log.error(e.getMessage());
				return;
			}
			if (keyPair == null) {
				log.warn("cannot find the provided public key");
				return;
			}
		}
		if (keyPair == null) {
			try {
				List<AKeyPair> keyPairList = mainParent.generateKeyPairs(1);
				keyPair = keyPairList.get(0);
				System.out.println("generated public key: " + keyPair.getAccountKey().toHexString());
			}
			catch (Error e) {
				log.error("failed to create key pair {}", e);
				return;
			}
		}

		Convex convex = null;
		try {

			convex = mainParent.connectToSessionPeer(
				hostname,
				port,
				Main.initConfig.getUserAddress(0),
				Main.initConfig.getUserKeyPair(0));

			Address address = convex.createAccount(keyPair.getAccountKey());

			log.info("account address: " + address);
			if (isFund) {
				convex.transferSync(address, Constants.ACCOUNT_FUND_AMOUNT);
				convex = mainParent.connectToSessionPeer(hostname, port, address, keyPair);
				Long balance = convex.getBalance(address);
				log.info("account balance: " + balance);
			}
			log.info(
				String.format(
					"to use this key can use the options --address=%d --public-key=%s",
					address.toLong(),
					Utils.toFriendlyHexString(keyPair.getAccountKey().toHexString(), 6)
				)
			);
		} catch (Throwable t) {
			log.error(t.getMessage());
			return;
		}
	}
}
