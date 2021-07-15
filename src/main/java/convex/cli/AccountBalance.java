package convex.cli;


import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Reader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	aliases={"bal", "ba"},
	mixinStandardHelpOptions=true,
	description="Get account balance.")
public class AccountBalance implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(AccountBalance.class);

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
	description="Address of the account to get the balance .")
	private long addressNumber;


	@Override
	public void run() {

		Main mainParent = accountParent.mainParent;

		if (addressNumber == 0) {
			log.warn("You need to provide a valid address number");
			return;
		}

		Convex convex = null;
		Address address = Address.create(addressNumber);
		try {
			convex = mainParent.connectToSessionPeer(hostname, port, address, null);
            String queryCommand = String.format("(balance #%d)", address.longValue());
			ACell message = Reader.read(queryCommand);
			Result result = convex.querySync(message, 5000);
			System.out.println(result);
		} catch (Throwable t) {
			log.error(t.getMessage());
			// t.printStackTrace();
			return;
		}

	}
}
