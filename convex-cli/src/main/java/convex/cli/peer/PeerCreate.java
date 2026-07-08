package convex.cli.peer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.mixins.RemotePeerMixin;
import convex.cli.output.RecordOutput;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.exceptions.ResultException;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 *  peer create command
 *
 *  This creates an peer on an existing network
 *
 *
 */
@Command(name="create",
	mixinStandardHelpOptions = true,
	description="Configures and creates a peer on a Convex network. Needs an existing peer as --host and a valid peer controller account. Will generate a new peer key if not otherwise specified.")
public class PeerCreate extends APeerCommand {

	@Mixin
	RemotePeerMixin peerMixin;

	@Override
	public void execute() throws InterruptedException {

		long peerStake = convex.core.cpos.CPoSConstants.MINIMUM_EFFECTIVE_STAKE;

		// create a keystore if it does not exist
		KeyStore keyStore = storeMixin.ensureKeyStore();

		try (Convex convex = peerMixin.connect()) {
			AKeyPair keyPair = AKeyPair.generate();

			// save the new peer keypair in the keystore
			PFXTools.setKeyPair(keyStore, keyPair, peerKeyMixin.getKeyPassword());
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
			ATransaction transaction = Invoke.create(address, ATransaction.UNKNOWN_SEQUENCE, message);
			Result result = convex.transactSync(transaction);
			if (result.isError()) {
				printResult(result);
				throw new CLIError("Peer creation transaction failed with error code: "+result.getErrorCode());
			}
			long currentBalance = convex.getBalance(address);

			String shortAccountKey = accountKeyString.substring(0, 6);

			RecordOutput output=new RecordOutput();
			output.addField("Public Peer Key", keyPair.getAccountKey().toString());
			output.addField("Controller Address", address.longValue());
			output.addField("Balance", currentBalance);
			output.addField("Initial stake amount", stakeAmount);

			output.addField("Peer start line",
				String.format(
					"./convex peer start --address=%d --peer-key=%s",
					address.longValue(),
					shortAccountKey
				)
			);
			printRecord(output);
		}  catch (IOException | GeneralSecurityException | ResultException t) {
			throw new CLIError("Error creating Peer",t);
		}
	}

}
