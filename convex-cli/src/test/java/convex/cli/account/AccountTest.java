package convex.cli.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;

@TestInstance(Lifecycle.PER_CLASS)
public class AccountTest {

	static final AKeyPair kp=AKeyPair.createSeeded(345634);
	protected static Server SERVER;
	protected static String PORT;

	@BeforeAll
	public static void setupPeer() throws LaunchException, ConfigException, InterruptedException {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		SERVER=API.launchPeer(config);
		PORT=Integer.toString(SERVER.getPort());

	}
	
	@Test
	public void testBalance() {
		CLTester tester;;
		
		AccountStatus as=SERVER.getPeer().getConsensusState().getAccount(Address.create(11));
		tester =  CLTester.run(
				"account", 
				"balance",
				"--port",PORT,
				"11"
			);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(""+as.getBalance(),tester.getOutput().trim());
		
		tester =  CLTester.run(
				"account", 
				"balance",
				"--port",PORT,
				"-a11"
			);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(""+as.getBalance(),tester.getOutput().trim());
		
		// No account specified
		tester =  CLTester.run(
				"account", 
				"balance",
				"--port",PORT
			);
		tester.assertExitCode(ExitCodes.USAGE);

	}
	
	@AfterAll
	public static void closePeer() {
		SERVER.shutdown();
	}
}
