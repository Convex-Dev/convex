package convex.cli.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.Main;
import convex.core.data.Address;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account fund command
 *
 *  convex.account.fund
 *
 */

@Command(name="fund",
    aliases={"fu"},
	mixinStandardHelpOptions=true,
	description="Transfers funds to account using a public/private key from the keystore.%n"
		+ "You must provide a valid keystore password to the keystore and a valid address.%n"
		+ "If the keystore is not at the default location also the keystore filename.")
public class AccountFund extends AAccountCommand {

	private static final Logger log = LoggerFactory.getLogger(AccountFund.class);

	@ParentCommand
	private Account accountParent;

	@Option(names={"-a", "--address"},
		description="Account address to use to request funds.")
	private long addressNumber;


	@Parameters(paramLabel="amount",
		defaultValue=""+Constants.ACCOUNT_FUND_AMOUNT,
		description="Amount to fund the account")
	private long amount;

	@Override
	public void run() {

		Main mainParent = accountParent.mainParent;


		if (addressNumber == 0) {
			log.warn("--address. You need to provide a valid address number");
			return;
		}

		Convex convex = null;
		Address address = Address.create(addressNumber);
		try {
			convex = connect();
			convex.transferSync(address, amount);
			Long balance = convex.getBalance(address);
			mainParent.println(balance);
		} catch (Throwable t) {
			throw new CLIError("Error funding account",t);
		}
	}
}
