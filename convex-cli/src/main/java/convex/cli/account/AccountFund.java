package convex.cli.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.core.cvm.Address;
import convex.core.util.JSON;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Convex account fund command
 *
 * Requests funds from the peer's faucet for an existing account.
 * This is useful for getting test coins on development networks.
 *
 * Note: Faucet is typically disabled on production networks.
 * For transferring between accounts, use: convex transact "(transfer #target amount)"
 *
 * convex account fund
 */
@Command(name="fund",
	aliases={"faucet"},
	mixinStandardHelpOptions=true,
	description="Request funds from peer's faucet for an existing account.%n%n" +
		"Note: Faucet is typically disabled on production networks like Protonet.%n" +
		"For transfers between accounts, use: convex transact \"(transfer #target amount)\"")
public class AccountFund extends AAccountCommand {

	@Option(names={"-a", "--address"},
		required=true,
		description="Account address to fund (e.g. #1234 or 1234).")
	private String addressSpec;

	@Parameters(paramLabel="amount",
		defaultValue=""+Constants.ACCOUNT_FUND_AMOUNT,
		description="Amount to request from faucet (default: ${DEFAULT-VALUE}). Max 1 CVX per request.")
	private long amount;

	@Override
	public void execute() throws InterruptedException {
		// Parse address using standard utility
		Address address = Address.parse(addressSpec);
		if (address == null) {
			throw new CLIError(ExitCodes.DATAERR, "Invalid address: " + addressSpec +
				". Use format #1234 or plain number.");
		}

		// Use faucet REST API
		String host = peerMixin.getSocketAddress().getHostString();
		int port = peerMixin.getSocketAddress().getPort();

		// Construct REST API URL
		int restPort = 8080; // Default REST API port
		String apiUrl = "http://" + host + ":" + restPort + "/api/v1/faucet";

		inform("Requesting " + amount + " coins from faucet at " + host + ":" + restPort);

		try {
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.build();

			// Build JSON request
			String json = "{\"address\":" + address.longValue() + ",\"amount\":" + amount + "}";

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.timeout(Duration.ofSeconds(30))
				.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 403) {
				throw new CLIError(ExitCodes.NOPERM,
					"Faucet not available on this peer. For transfers between accounts, use:\n" +
					"  convex transact -a <source> --key <key> \"(transfer " + address + " " + amount + ")\"");
			}

			if (response.statusCode() == 400) {
				throw new CLIError(ExitCodes.DATAERR,
					"Bad request: " + response.body());
			}

			if (response.statusCode() != 200) {
				throw new CLIError(ExitCodes.TEMPFAIL,
					"Faucet request failed (HTTP " + response.statusCode() + "): " + response.body());
			}

			// Parse response
			String body = response.body();
			Object parsed = JSON.parse(body);
			if (parsed instanceof java.util.Map) {
				@SuppressWarnings("unchecked")
				java.util.Map<String, Object> map = (java.util.Map<String, Object>) parsed;

				// Check for error in response
				Object errorCode = map.get("errorCode");
				if (errorCode != null) {
					Object errorMsg = map.get("value");
					throw new CLIError(ExitCodes.TEMPFAIL,
						"Faucet request failed: " + (errorMsg != null ? errorMsg : errorCode));
				}

				// Success - get the value (amount transferred)
				Object value = map.get("value");
				if (value != null) {
					inform("Funded " + address + " with " + value + " coins");
					println(value);
					return;
				}
			}

			// Fallback success message
			inform("Funded " + address + " with " + amount + " coins");
			println(amount);

		} catch (CLIError e) {
			throw e;
		} catch (java.net.ConnectException e) {
			throw new CLIError(ExitCodes.NOHOST,
				"Cannot connect to REST API at " + apiUrl + ". Check if peer has REST API enabled on port " + restPort + ".");
		} catch (Exception e) {
			throw new CLIError(ExitCodes.TEMPFAIL, "Faucet request failed: " + e.getMessage(), e);
		}
	}
}
