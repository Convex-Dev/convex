package convex.cli;

import java.util.List;

import convex.api.Convex;
import convex.cli.output.RecordOutput;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.util.Utils;
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
	mixinStandardHelpOptions=true,
	description="Creates an account on Convex.")
public class AccountCreate implements Runnable {

	@ParentCommand
	private Account accountParent;

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
		RecordOutput output=new RecordOutput();

		AKeyPair keyPair = null;

		if (!keystorePublicKey.isEmpty()) {
			keyPair = mainParent.storeMixin.loadKeyFromStore(mainParent, keystorePublicKey);
			if (keyPair == null) {
				throw new CLIError("Cannot find the provided public key in keystore: "+keystorePublicKey);
			}
		}
		
		if (keyPair == null) {
			List<AKeyPair> keyPairList = mainParent.generateKeyPairs(1,mainParent.getKeyPassword());
			keyPair = keyPairList.get(0);
			output.addField("Public Key", keyPair.getAccountKey().toHexString());
		}

		Convex convex = null;
		try {

			convex = mainParent.connect();

			Address address = convex.createAccountSync(keyPair.getAccountKey());
			output.addField("Address", address.longValue());
			if (isFund) {
				convex.transferSync(address, Constants.ACCOUNT_FUND_AMOUNT);
				Long balance = convex.getBalance(address);
				output.addField("Balance", balance);
			}
			output.addField("Account usage",
				String.format(
					"to use this key can use the options --address=%d --public-key=%s",
					address.longValue(),
					Utils.toFriendlyHexString(keyPair.getAccountKey().toHexString(), 6)
				)
			);
			output.writeToStream(mainParent.commandLine.getOut());
		} catch (Throwable t) {
			throw new CLIError("Error creating account",t);
		}
	}
}
