package convex.cli.account;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.AccountKey;
import convex.core.exceptions.ResultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Convex account create command
 *
 * Creates a new account on a Convex network. Can optionally generate a new key
 * or use an existing key from the keystore. Will attempt to fund the account
 * via faucet if requested.
 *
 * convex account create
 */
@Command(name="create",
	mixinStandardHelpOptions=true,
	description="Creates an account on Convex. Requires a peer with faucet capability.")
public class AccountCreate extends AAccountCommand {

	@ParentCommand
	private Account accountParent;

	@Mixin
	protected KeyStoreMixin storeMixin;

	@Mixin
	protected KeyMixin keyMixin;

	@Option(names={"--new-key"},
		description="Generate a new key for the account instead of using an existing one.")
	private boolean generateNewKey;

	@Option(names={"-f", "--fund"},
		description="Create and fund account via faucet.")
	private boolean isFund;

	@Option(names={"--fund-amount"},
		defaultValue=""+Constants.ACCOUNT_FUND_AMOUNT,
		description="Amount to fund the account (default: ${DEFAULT-VALUE}).")
	private long fundAmount;

	@Override
	public void execute() throws InterruptedException {
		AKeyPair keyPair = null;
		AccountKey accountKey = null;
		boolean newKeyGenerated = false;

		// Determine which key to use for the new account
		String specifiedKey = keyMixin.getPublicKey();

		if (generateNewKey) {
			// Generate a new key pair
			keyPair = AKeyPair.generate();
			accountKey = keyPair.getAccountKey();
			newKeyGenerated = true;
			inform("Generated new key pair with public key: 0x" + accountKey.toChecksumHex());
		} else if (specifiedKey != null && !specifiedKey.isBlank()) {
			// Use specified key from keystore or parse as public key
			accountKey = AccountKey.parse(specifiedKey);
			if (accountKey == null) {
				// Try to load from keystore
				keyPair = storeMixin.loadKeyFromStore(specifiedKey, () -> keyMixin.getKeyPassword());
				if (keyPair == null) {
					throw new CLIError(ExitCodes.DATAERR, "Invalid key specification: " + specifiedKey +
						". Must be a valid 32-byte hex public key or a key prefix in the keystore.");
				}
				accountKey = keyPair.getAccountKey();
			}
		} else {
			// No key specified - generate one automatically
			keyPair = AKeyPair.generate();
			accountKey = keyPair.getAccountKey();
			newKeyGenerated = true;
			inform("No new key specified, generated new key pair with public key: 0x" + accountKey.toChecksumHex());
		}

		// Connect to peer
		Convex convex = connect();

		// Create the account
		Address newAddress;
		try {
			newAddress = convex.createAccountSync(accountKey);
		} catch (ResultException e) {
			throw new CLIError(ExitCodes.TEMPFAIL, "Failed to create account: " + e.getResult().getValue() +
				". The peer may not support account creation or faucet may be unavailable.", e);
		}

		inform("Created account: " + newAddress);

		// Store the key pair if we generated a new one
		if (newKeyGenerated && keyPair != null) {
			char[] pass = keyMixin.getKeyPassword();
			storeMixin.ensureKeyStore();
			storeMixin.addKeyPairToStore(keyPair, pass);
			storeMixin.saveKeyStore();
			informSuccess("Key pair stored in keystore: " + storeMixin.getStorePath());
		}

		// Fund the account if requested
		if (isFund) {
			try {
				convex.transferSync(newAddress, fundAmount);
				Long balance = convex.getBalance(newAddress);
				inform("Funded account with " + fundAmount + " coins. Balance: " + balance);
			} catch (ResultException e) {
				informWarning("Account created but funding failed: " + e.getResult().getValue() +
					". Faucet may not be available on this peer.");
			}
		}

		// Output the new address
		println(newAddress.toString());
	}

	@Override
	public Main cli() {
		return accountParent.cli();
	}
}
