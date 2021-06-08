package convex.cli;

import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 *
 *  Convex account infomation command
 *
 *  convex.account.infomation
 *
 */

@Command(name="information",
	aliases={"info", "in"},
	mixinStandardHelpOptions=true,
	description="Get account information.")
public class AccountInformation implements Runnable {

	private static final Logger log = Logger.getLogger(AccountInformation.class.getName());

	@ParentCommand
	private Account accountParent;

	@Option(names={"--port"},
		description="Port number to connect to a peer.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;


	@Parameters(paramLabel="address",
	description="Address of the account to get information.")
	private long addressNumber;


	@Override
	public void run() {

		Main mainParent = accountParent.mainParent;

		if (addressNumber == 0) {
			log.severe("You need to provide a valid address number");
			return;
		}

		Convex convex = null;
		Address address = Address.create(addressNumber);
		try {
			convex = mainParent.connectToSessionPeer(hostname, port, address, null);
            String queryCommand = String.format("(account #%d)", address.longValue());
			ACell message = Reader.read(queryCommand);
			Result result = convex.querySync(message, 5000);
			System.out.println(result);
		} catch (Throwable t) {
			log.severe(t.getMessage());
			t.printStackTrace();
			return;
		}

	}
}
