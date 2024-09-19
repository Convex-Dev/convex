package convex.cli.peer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.mixins.RemotePeerMixin;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.exceptions.ResultException;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 *  peer create command
 *
 *		convex.peer.create
 *
 *  This creates an peer on an existing network
 *
 *
 */
@Command(name="create",
	mixinStandardHelpOptions = true, 
	description="Configures and creates a peer on a Convex network. Needs an esisting peer as --host and a valid peer controller account. Will generate a new peer key if not otherwise specified.")
public class PeerCreate extends APeerCommand {

	private static final Logger log = LoggerFactory.getLogger(PeerCreate.class);

	@Spec CommandSpec spec;

	@Mixin
	RemotePeerMixin peerMixin;

	@Override
	public void execute() throws InterruptedException {

		long peerStake = convex.core.Constants.MINIMUM_EFFECTIVE_STAKE;

		AKeyPair keyPair = null;
		KeyStore keyStore;

		try {
			// create a keystore if it does not exist
			keyStore = storeMixin.ensureKeyStore();
		} catch (Error e) {
			log.info(e.getMessage());
			return;
		}

		try {
			// connect using the default first user
			Convex convex = peerMixin.connect();
			
			keyPair = AKeyPair.generate();

			// save the new keypair in the keystore
			PFXTools.setKeyPair(keyStore, keyPair, keyMixin.getKeyPassword());
			storeMixin.saveKeyStore();
			inform("Created new peer key: "+keyPair.getAccountKey());

			// create an account
			Address address = convex.createAccountSync(keyPair.getAccountKey());
			convex.transferSync(address, peerStake);

			long stakeBalance = convex.getBalance(address);
			String accountKeyString = keyPair.getAccountKey().toHexString();
			long stakeAmount = (long) (stakeBalance * 0.98);

			String transactionCommand = String.format("(create-peer 0x%s %d)", accountKeyString, stakeAmount);
			ACell message = Reader.read(transactionCommand);
			ATransaction transaction = Invoke.create(address, -1, message);
			Result result = convex.transactSync(transaction);
			if (result.isError()) {
                printResult(result);
				return;
			}
			long currentBalance = convex.getBalance(address);

			String shortAccountKey = accountKeyString.substring(0, 6);

			RecordOutput output=new RecordOutput();			
			output.addField("Public Peer Key", keyPair.getAccountKey().toString());
			output.addField("Controller Address", address.longValue());
			output.addField("Balance", currentBalance);
			output.addField("Inital stake amount", stakeAmount);
			// System.out.println("You can now start this peer by executing the following line:\n");

			// WARNING not sure about showing the users password..
			// to make the starting of peers easier, I have left it in for a simple copy/paste

			output.addField("Peer start line",
				String.format(
					"./convex peer start --address=%d --peer-key=%s",
					address.longValue(),
					shortAccountKey
				)
			);
			output.writeToStream(cli().commandLine().getOut());
		}  catch (IOException | GeneralSecurityException | ResultException t) {
			throw new CLIError("Error creating Peer",t);
		}
	}

}
