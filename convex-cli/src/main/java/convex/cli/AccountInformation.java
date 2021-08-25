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

	private static final Logger log = LoggerFactory.getLogger(AccountInformation.class);

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

    @Option(names={"-t", "--timeout"},
		description="Timeout in miliseconds.")
	private long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;


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
            String queryCommand = String.format("(account #%d)", address.longValue());
			ACell message = Reader.read(queryCommand);
			Result result = convex.querySync(message, timeout);
			mainParent.output.setResult(result.getValue(), result.getErrorCode(), result.getTrace());
		} catch (Throwable t) {
			mainParent.showError(t);
		}

	}
}
