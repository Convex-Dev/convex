package convex.cli;

import org.junit.jupiter.api.Test;

import static convex.cli.HelperTest.assertExecuteCommandLineResult;

public class CLIHelpTest {

	@Test
	public void testHelp() {
		assertExecuteCommandLineResult(0, "^Usage: convex ", "--help", "--no-color");
		assertExecuteCommandLineResult(0, "^Usage: convex ", "-h", "--no-color");
		assertExecuteCommandLineResult(0, "^Usage: convex account ", "account", "help");
		assertExecuteCommandLineResult(0, "^Usage: convex account balance ", "account",  "balance", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account create ", "account",  "create", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account info ", "account",  "info", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account fund ", "account",  "fund", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex key ", "key", "help");
		assertExecuteCommandLineResult(0, "^Usage: convex key generate ", "key", "generate", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex key list ", "key", "list", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex peer ", "peer", "-h", "--no-color");
		assertExecuteCommandLineResult(0, "^Usage: convex peer start ", "peer", "start", "--help", "--no-color");
		assertExecuteCommandLineResult(0, "^Usage: convex local ", "local", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex local start ", "local", "start", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex local gui ", "local", "gui", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex query ", "query", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex status ", "status", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex transact ", "transact", "--help");
		// assertExecuteCommandLineResult(0, "^Usage: convex \\[", "help", "--help", "--no-color");
	}

}
