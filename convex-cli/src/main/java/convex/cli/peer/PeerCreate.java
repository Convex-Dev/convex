package convex.cli.peer;

import java.security.KeyStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.Main;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 *  peer create command
 *
 *		convex.peer.create
 *
 *  This creates an account and provides enougth funds, for a new peer account
 *
 *
 */

@Command(name="create",
	aliases={"cr"},
	description="Creates a keypair, new account and a funding stake: to run a local peer.")
public class PeerCreate extends APeerCommand {

	private static final Logger log = LoggerFactory.getLogger(PeerCreate.class);

	@Spec CommandSpec spec;

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to use for the peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names={"--port"},
		description="Port number of nearest peer to connect too.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	@Option(names={"-t", "--timeout"},
		description="Timeout in miliseconds.")
	private long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;


	@Override
	public void run() {

		Main mainParent = cli();

		long peerStake = convex.core.Constants.MINIMUM_EFFECTIVE_STAKE;

		AKeyPair keyPair = null;
		KeyStore keyStore;

		try {
			// create a keystore if it does not exist
			keyStore = mainParent.loadKeyStore();
		} catch (Error e) {
			log.info(e.getMessage());
			return;
		}

		try {
			keyPair = AKeyPair.generate();

			// save the new keypair in the keystore
			PFXTools.setKeyPair(keyStore, keyPair, mainParent.getKeyPassword());
			mainParent.saveKeyStore();

			// connect using the default first user
			Convex convex = mainParent.connect();
			// create an account
			Address address = convex.createAccountSync(keyPair.getAccountKey());
			convex.transferSync(address, peerStake);

			long stakeBalance = convex.getBalance(address);
			String accountKeyString = keyPair.getAccountKey().toHexString();
			long stakeAmount = (long) (stakeBalance * 0.98);

			String transactionCommand = String.format("(create-peer 0x%s %d)", accountKeyString, stakeAmount);
			ACell message = Reader.read(transactionCommand);
			ATransaction transaction = Invoke.create(address, -1, message);
			Result result = convex.transactSync(transaction, timeout);
			if (result.isError()) {
                cli().printResult(result);
				return;
			}
			long currentBalance = convex.getBalance(address);

			String shortAccountKey = accountKeyString.substring(0, 6);
			RecordOutput output=new RecordOutput();
			
			output.addField("Public Peer Key", keyPair.getAccountKey().toString());
			output.addField("Address", address.longValue());
			output.addField("Balance", currentBalance);
			output.addField("Inital stake amount", stakeAmount);
			// System.out.println("You can now start this peer by executing the following line:\n");

			// WARNING not sure about showing the users password..
			// to make the starting of peers easier, I have left it in for a simple copy/paste

			output.addField("Peer start line",
				String.format(
					"./convex peer start --password=xx --address=%d --public-key=%s",
					address.longValue(),
					shortAccountKey
				)
			);
			output.writeToStream(mainParent.commandLine.getOut());
		} catch (Throwable t) {
			throw new CLIError("Error creating Peer",t);
		}
	}

}
