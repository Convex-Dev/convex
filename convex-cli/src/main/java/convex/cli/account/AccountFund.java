package convex.cli.account;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.core.cvm.Address;
import convex.core.exceptions.ResultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Convex account fund command
 *
 * convex account fund
 */
@Command(name="fund",
    aliases={"fu"},
	mixinStandardHelpOptions=true,
	description="Transfers funds to account using a public/private key from the keystore.%n"
		+ "You must provide a valid keystore password to the keystore and a valid address.%n"
		+ "If the keystore is not at the default location also the keystore filename.")
public class AccountFund extends AAccountCommand {

	@Option(names={"-a", "--address"},
		required=true,
		description="Account address to fund (e.g. #1234 or 1234).")
	private String addressSpec;

	@Parameters(paramLabel="amount",
		defaultValue=""+Constants.ACCOUNT_FUND_AMOUNT,
		description="Amount to fund the account (default: ${DEFAULT-VALUE}).")
	private long amount;

	@Override
	public void execute() throws InterruptedException {
		// Parse address using standard utility
		Address address = Address.parse(addressSpec);
		if (address == null) {
			throw new CLIError(ExitCodes.DATAERR, "Invalid address: " + addressSpec +
				". Use format #1234 or plain number.");
		}

		Convex convex = connect();
		try {
			convex.transferSync(address, amount);
			Long balance = convex.getBalance(address);
			inform("Funded " + address + " with " + amount + " coins.");
			println(balance);
		} catch (ResultException e) {
			throw new CLIError(ExitCodes.TEMPFAIL, "Error funding account: " + e.getResult().getValue(), e);
		}
	}
}
