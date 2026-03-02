package convex.cli.account;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.cvm.Address;
import convex.core.data.List;
import convex.core.cvm.Symbols;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Convex account information command
 *
 * convex account info
 */
@Command(name="info",
	mixinStandardHelpOptions=true,
	description="Get account information.")
public class AccountInformation extends AAccountCommand {

	@Parameters(paramLabel="address",
		arity="1",
		description="Address of the account to get information (e.g. #1234 or 1234).")
	private String addressValue;

	@Override
	public void execute() throws InterruptedException {
		Address address = Address.parse(addressValue);
		if (address == null) {
			throw new CLIError(ExitCodes.DATAERR, "Invalid address: " + addressValue +
				". Use format #1234 or plain number.");
		}

		Convex convex = connect();
		ACell queryCommand = List.of(Symbols.ACCOUNT, address);
		Result result = convex.querySync(queryCommand);
		printResult(result);
	}
}
