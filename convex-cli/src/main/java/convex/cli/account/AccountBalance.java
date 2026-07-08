package convex.cli.account;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.cvm.Address;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Convex account balance command
 *
 * convex account balance
 */
@Command(name="balance",
	aliases={"bal"},
	mixinStandardHelpOptions=true,
	description="Get account balance of the specified address.")
public class AccountBalance extends AAccountCommand {

	@Parameters(paramLabel="addresses",
		description="Address(es) to query balance for. If omitted, will look for --address argument.")
	private String[] addresses;

	@Override
	public void execute() throws InterruptedException {
		if (addresses == null) {
			Address address = addressMixin.getSpecifiedAddress();
			if (address != null) {
				addresses = new String[] { address.toString() };
			} else {
				showUsage();
				throw new CLIError(ExitCodes.USAGE, "No addresses specified");
			}
		}

		int n = addresses.length;

		StringBuilder sb = new StringBuilder();
		sb.append("(map balance [");
		for (int i = 0; i < n; i++) {
			String aString = addresses[i];
			Address addr = Address.parse(aString);
			if (addr == null) {
				throw new CLIError(ExitCodes.DATAERR, "Invalid address: " + aString);
			}
			sb.append(addr);
			sb.append(' ');
		}
		sb.append("])");

		try (Convex convex = connect()) {
			ACell message = Reader.read(sb.toString());
			Result result = convex.querySync(message);
			if (result.isError()) {
				throw new CLIError("Balance query failed: " + result.toString());
			} else {
				AVector<ACell> v = RT.ensureVector(result.getValue());
				if (v == null) throw new CLIError(ExitCodes.DATAERR, "Invalid result, expected vector of balances: " + result);
				for (int i = 0; i < n; i++) {
					println(v.get(i));
				}
			}
		}
	}
}
