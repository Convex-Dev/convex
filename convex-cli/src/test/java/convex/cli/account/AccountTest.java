package convex.cli.account;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.Keyword;
import convex.core.cvm.Keywords;
import convex.core.store.MemoryStore;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;

@TestInstance(Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
public class AccountTest {

	static final AKeyPair kp=AKeyPair.createSeeded(345634);
	protected static MemoryStore STORE;
	protected static Server SERVER;
	protected static String PORT;

	@BeforeAll
	public static void setupPeer() throws LaunchException, ConfigException, InterruptedException {
		STORE=new MemoryStore();
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keywords.STORE, STORE);
		config.put(Keywords.PORT, 0);
		SERVER=API.launchPeer(config);
		PORT=Integer.toString(SERVER.getPort());
	}
	
	@Test
	public void testBalance() {
		CLTester tester;;
		
		convex.core.cvm.AccountStatus as=SERVER.getPeer().getConsensusState().getAccount(Address.create(11));
		tester =  CLTester.run(
				"account",
				"balance",
				"--host", "localhost",
				"--port",PORT,
				"11"
			);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(""+as.getBalance(),tester.getOutput().trim());

		tester =  CLTester.run(
				"account",
				"balance",
				"--host", "localhost",
				"--port",PORT,
				"-a11"
			);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(""+as.getBalance(),tester.getOutput().trim());

		// No account specified
		tester =  CLTester.run(
				"account",
				"balance",
				"--host", "localhost",
				"--port",PORT
			);
		tester.assertExitCode(ExitCodes.USAGE);

	}
	
	@AfterAll
	public static void closePeer() {
		// The server borrows the store, so it closes first
		assertAll("Close the test peer and its store",
			() -> { if (SERVER!=null) SERVER.close(); },
			() -> { if (STORE!=null) STORE.close(); });
	}
}
