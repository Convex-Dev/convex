package convex.cli;

import java.io.File;
import java.security.KeyStore;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.Address;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
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
	mixinStandardHelpOptions=true,
	description="Creates a keypair, new account and a funding stake: to run a local peer.")
public class PeerCreate implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(PeerCreate.class);

	@ParentCommand
	private Peer peerParent;

	@Spec CommandSpec spec;

	@Option(names={"-i", "--index-key"},
		defaultValue="0",
		description="Keystore index of the public/private key to use for the peer.")
	private int keystoreIndex;

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

		Main mainParent = peerParent.mainParent;

		int port = 0;
		long peerStake = 10000000000L;

		AKeyPair keyPair = null;
		KeyStore keyStore;

		try {
			// create a keystore if it does not exist
			keyStore = mainParent.loadKeyStore(true);
		} catch (Error e) {
			log.info(e.getMessage());
			return;
		}

		try {
			keyPair = AKeyPair.generate();

			// save the new keypair in the keystore
			PFXTools.saveKey(keyStore, keyPair, mainParent.getPassword());

			File keyFile = new File(mainParent.getKeyStoreFilename());

			// save the store to a file
			PFXTools.saveStore(keyStore, keyFile, mainParent.getPassword());

			// connect using the default first user
			Convex convex = mainParent.connectToSessionPeer(
				hostname,
				port,
				Main.initConfig.getUserAddress(0),
				Main.initConfig.getUserKeyPair(0));

			// create an account
			Address address = convex.createAccount(keyPair.getAccountKey());
			convex.transferSync(address, peerStake);

			convex = mainParent.connectToSessionPeer(hostname, port, address, keyPair);
			long stakeBalance = convex.getBalance(address);
			String accountKeyString = keyPair.getAccountKey().toHexString();
			long stakeAmount = (long) (stakeBalance * 0.98);

			String transactionCommand = String.format("(create-peer 0x%s %d)", accountKeyString, stakeAmount);
			ACell message = Reader.read(transactionCommand);
			ATransaction transaction = Invoke.create(address, -1, message);
			Result result = convex.transactSync(transaction, timeout);
			if (result.isError()) {
				log.error("cannot create peer on the network: {} {} ", result.getErrorCode(), result.getTrace());
				return;
			}
			long currentBalance = convex.getBalance(address);

			mainParent.output.setField("Public Peer Key", keyPair.getAccountKey().toString());
			mainParent.output.setField("Address", address.longValue());
			mainParent.output.setField("Balance", currentBalance);
			mainParent.output.setField("Inital stake amount", stakeAmount);
			String shortAccountKey = accountKeyString.substring(0, 6);
			// System.out.println("You can now start this peer by executing the following line:\n");

			// WARNING not sure about showing the users password..
			// to make the starting of peers easier, I have left it in for a simple copy/paste

			mainParent.output.setField("Peer start line",
				String.format(
					"./convex peer start --password=%s --address=%d --public-key=%s",
					mainParent.getPassword(),
					address.toLong(),
					shortAccountKey
				)
			);
		} catch (Throwable t) {
			log.error("Unable to launch peer {}", t);
		}
	}
}
