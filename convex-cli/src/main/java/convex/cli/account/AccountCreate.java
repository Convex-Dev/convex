package convex.cli.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.AddressMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.AccountKey;
import convex.core.exceptions.ResultException;
import convex.core.util.JSON;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Convex account create command
 *
 * Creates a new account on a Convex network. Two modes of operation:
 *
 * 1. Transaction mode (default): Uses an existing funded account to run
 *    (create-account key) transaction. Requires --address and --key for
 *    the funding account.
 *
 * 2. Faucet mode (--faucet): Uses the peer's REST API faucet endpoint to
 *    create and optionally fund a new account. Only works if the peer has
 *    faucet enabled.
 *
 * convex account create
 */
@Command(name="create",
	mixinStandardHelpOptions=true,
	description="Creates an account on Convex.%n%n" +
		"Two modes of operation:%n" +
		"  1. Transaction mode: Use existing funded account (requires -a/--address and --key)%n" +
		"  2. Faucet mode (--faucet): Use peer's faucet API (if available)%n%n" +
		"Note: Faucet is typically disabled on production networks like Protonet.")
public class AccountCreate extends AAccountCommand {

	@ParentCommand
	private Account accountParent;

	@Mixin
	protected KeyStoreMixin storeMixin;

	@Mixin
	protected KeyMixin keyMixin;

	@Mixin
	protected AddressMixin addressMixin;

	@Option(names={"--new-key"},
		description="Generate a new key for the new account.")
	private boolean generateNewKey;

	@Option(names={"--new-account-key"},
		description="Public key (hex) for the new account. If not specified, generates a new key.")
	private String newAccountKeySpec;

	@Option(names={"--faucet"},
		description="Use faucet API to create account (peer must have faucet enabled).")
	private boolean useFaucet;

	@Option(names={"--faucet-amount"},
		defaultValue=""+Constants.ACCOUNT_FUND_AMOUNT,
		description="Amount to request from faucet when using --faucet (default: ${DEFAULT-VALUE}).")
	private long faucetAmount;

	@Override
	public void execute() throws InterruptedException {
		// Validate requirements BEFORE generating keys
		if (!useFaucet) {
			// Transaction mode: validate funding account params early with clear messages
			String keySpec = keyMixin.getPublicKey();
			boolean hasKey = keySpec != null && !keySpec.isBlank();

			// Check what's missing and provide helpful error message
			try {
				addressMixin.getAddress("Enter funding account address: ");
			} catch (CLIError e) {
				// Enhance error message for transaction mode
				throw new CLIError(ExitCodes.USAGE,
					"Transaction mode requires -a/--address and --key for the funding account.\n" +
					"Use --faucet to create account via faucet instead (if available).");
			}

			if (!hasKey) {
				throw new CLIError(ExitCodes.USAGE,
					"Transaction mode requires --key for the funding account.\n" +
					"Use --faucet to create account via faucet instead (if available).");
			}
		}

		AKeyPair newKeyPair = null;
		AccountKey newAccountKey = null;
		boolean newKeyGenerated = false;

		// Determine key for the NEW account being created
		if (newAccountKeySpec != null && !newAccountKeySpec.isBlank()) {
			// Use specified public key for new account
			newAccountKey = AccountKey.parse(newAccountKeySpec);
			if (newAccountKey == null) {
				throw new CLIError(ExitCodes.DATAERR, "Invalid new account key: " + newAccountKeySpec +
					". Must be a valid 32-byte hex public key.");
			}
		} else if (generateNewKey || newAccountKeySpec == null) {
			// Generate a new key pair for the new account
			newKeyPair = AKeyPair.generate();
			newAccountKey = newKeyPair.getAccountKey();
			newKeyGenerated = true;
		}

		// Store the new key pair locally BEFORE network operations
		if (newKeyGenerated && newKeyPair != null) {
			char[] pass = keyMixin.getKeyPassword();
			storeMixin.ensureKeyStore();
			storeMixin.addKeyPairToStore(newKeyPair, pass);
			storeMixin.saveKeyStore();
			inform("Generated new key pair for account");
			inform("  Public key: 0x" + newAccountKey.toChecksumHex());
			inform("  Stored in:  " + storeMixin.getStorePath());
		}

		Address newAddress;

		if (useFaucet) {
			// Faucet mode: use REST API
			newAddress = createViaFaucet(newAccountKey);
		} else {
			// Transaction mode: use existing account
			newAddress = createViaTransaction(newAccountKey);
		}

		inform("Created account: " + newAddress);

		// Output the new address (machine-readable)
		println(newAddress.toString());
	}

	/**
	 * Create account using transaction from existing funded account.
	 * Note: Assumes address and key have been validated in execute().
	 */
	private Address createViaTransaction(AccountKey newAccountKey) throws InterruptedException {
		// Get validated params (already checked in execute())
		Address fundingAddress = addressMixin.getAddress(null);
		String keySpec = keyMixin.getPublicKey();

		// Load the funding account's key pair
		AKeyPair fundingKeyPair = storeMixin.loadKeyFromStore(keySpec, () -> keyMixin.getKeyPassword());
		if (fundingKeyPair == null) {
			throw new CLIError(ExitCodes.DATAERR, "Cannot find key in keystore: " + keySpec);
		}

		// Connect and create account via transaction
		Convex convex = connect();
		convex.setAddress(fundingAddress);
		convex.setKeyPair(fundingKeyPair);

		try {
			return convex.createAccountSync(newAccountKey);
		} catch (ResultException e) {
			throw new CLIError(ExitCodes.TEMPFAIL, "Failed to create account: " + e.getResult().getValue(), e);
		}
	}

	/**
	 * Create account using peer's faucet REST API
	 */
	private Address createViaFaucet(AccountKey newAccountKey) {
		String host = peerMixin.getSocketAddress().getHostString();
		int port = peerMixin.getSocketAddress().getPort();

		// Construct REST API URL (assumes HTTP, port 8080 for REST by convention)
		// TODO: Make REST port configurable
		int restPort = 8080; // Default REST API port
		String apiUrl = "http://" + host + ":" + restPort + "/api/v1/createAccount";

		inform("Requesting account creation via faucet at " + host + ":" + restPort);

		try {
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.build();

			// Build JSON request
			StringBuilder json = new StringBuilder();
			json.append("{\"accountKey\":\"").append(newAccountKey.toHexString()).append("\"");
			if (faucetAmount > 0) {
				json.append(",\"faucet\":").append(faucetAmount);
			}
			json.append("}");

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.toString()))
				.timeout(Duration.ofSeconds(30))
				.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 403) {
				throw new CLIError(ExitCodes.NOPERM,
					"Faucet not available on this peer. Use transaction mode with --address and --key instead.");
			}

			if (response.statusCode() != 200) {
				throw new CLIError(ExitCodes.TEMPFAIL,
					"Faucet request failed (HTTP " + response.statusCode() + "): " + response.body());
			}

			// Parse response to get address
			String body = response.body();
			Object parsed = JSON.parse(body);
			if (parsed instanceof java.util.Map) {
				@SuppressWarnings("unchecked")
				java.util.Map<String, Object> map = (java.util.Map<String, Object>) parsed;
				Object addrObj = map.get("address");
				if (addrObj != null) {
					Address addr = Address.parse(addrObj);
					if (addr != null) {
						if (faucetAmount > 0) {
							inform("Faucet funded account with " + faucetAmount + " coins");
						}
						return addr;
					}
				}
			}

			throw new CLIError(ExitCodes.DATAERR, "Invalid faucet response: " + body);

		} catch (CLIError e) {
			throw e;
		} catch (java.net.ConnectException e) {
			throw new CLIError(ExitCodes.NOHOST,
				"Cannot connect to REST API at " + apiUrl + ". Check if peer has REST API enabled on port " + restPort + ".");
		} catch (Exception e) {
			throw new CLIError(ExitCodes.TEMPFAIL, "Faucet request failed: " + e.getMessage(), e);
		}
	}

	@Override
	public Main cli() {
		return accountParent.cli();
	}
}
