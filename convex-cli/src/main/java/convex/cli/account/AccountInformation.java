package convex.cli.account;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.List;
import convex.core.data.Symbols;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account information command
 *
 *  convex.account.infomation
 *
 */

@Command(name="info",
	mixinStandardHelpOptions=true,
	description="Get account information.")
public class AccountInformation extends AAccountCommand {
	@ParentCommand
	private Account accountParent;

	@Parameters(paramLabel="address",
	description="Address of the account to get information.")
	private String addressValue;

	@Override
	public void execute() throws InterruptedException {
		if (addressValue==null) {
			this.informWarning("You need to provide an address / account number to get information");
			showUsage();
			return;
		}
		
		Address address=Address.parse(addressValue);
		if (address==null) {
			throw new CLIError(ExitCodes.DATAERR,"Address cannot be parsed.");
		}
		
		Convex convex = connect();
		ACell queryCommand = List.of(Symbols.ACCOUNT,address);
		Result result = convex.querySync(queryCommand);
		printResult(result);

	}
}
