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
		arity="0..1",
		description="Address of the account to get information (e.g. #1234 or 1234). If omitted, will look for --address argument.")
	private String addressValue;

	@Override
	public void execute() throws InterruptedException {
		Address address;
		if (addressValue != null) {
			address = Address.parse(addressValue);
			if (address == null) {
				throw new CLIError(ExitCodes.DATAERR, "Invalid address: " + addressValue +
					". Use format #1234 or plain number.");
			}
		} else {
			address = addressMixin.getAddress("Enter account address: ");
		}

		try (Convex convex = connect()) {
			ACell queryCommand = List.of(Symbols.ACCOUNT, address);
			Result result = convex.querySync(queryCommand);
			printResult(result);
			if (result.isError()) {
				throw new CLIError("Account query failed with error code: "+result.getErrorCode());
			}
		}
	}
}
