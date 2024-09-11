package convex.cli.account;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account balance command
 *
 *  convex.account.balance
 *
 */

@Command(name="balance",
	aliases={"bal"},
	mixinStandardHelpOptions=true,
	description="Get account balance of the specified address.")
public class AccountBalance extends AAccountCommand {
 
	@ParentCommand
	private Account accountParent;

	@Parameters(paramLabel="addresses",
	description="Address(es) to query balance for. If omitted, will look for --address argument.")
	private String[] addresses;
	
	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use for query. Can specify with CONVEX_ADDRESS environment variable.")
	protected String addressValue = null;

	@Override
	public void execute() throws InterruptedException {
		Address address=Address.parse(addressValue);
		if (addresses==null) {
			if (address!=null) {
				addresses=new String[] {address.toString()};
			} else {
				showUsage();
				throw new CLIError(ExitCodes.USAGE,"No addressed specified");
			}
		}		
		
		int n = addresses.length;

		Convex convex = peerMixin.connect();
		try {
			StringBuilder sb=new StringBuilder();
			sb.append("(map balance [");
			for (int i=0; i<n; i++) {
				String aString=addresses[i];
				Address addr=Address.parse(aString);
				if (addr==null) {
					throw new CLIError(ExitCodes.USAGE,"Address not valid: "+aString);
				}
				sb.append(addr);
			}
			sb.append("])");
			ACell message = Reader.read(sb.toString());
			Result result = convex.querySync(message);
			if (result.isError()) {
				throw new CLIError("Balance query failed: " +result.toString());
			} else {
				AVector<ACell> v=RT.ensureVector(result.getValue());
				if (v==null) throw new CLIError(ExitCodes.DATAERR,"Invalid result, expected vector of balances: "+result);
				for (int i=0; i<n; i++) {
					println(v.get(i));
				}
			}
		} finally  {
			convex.close();
		}
	}
}
