package convex.cli;

import org.junit.jupiter.api.Test;

import static convex.cli.Helper.assertExecuteCommandLineResult;

public class CLIHelpTest {

	@Test
	public void testHelp() {
		assertExecuteCommandLineResult(0, "^Usage: convex \\[-hVv\\]", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex \\[-hVv\\]", "-h");
		assertExecuteCommandLineResult(0, "^Usage: convex \\[-hVv\\]", "help");
		assertExecuteCommandLineResult(0, "^Usage: convex account \\[-hVv\\]", "account", "help");
		assertExecuteCommandLineResult(0, "^Usage: convex account balance \\[-hVv\\]", "account",  "balance", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account create \\[-fhVv\\]", "account",  "create", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account information \\[-hVv\\]", "account",  "information", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account fund \\[-hVv\\]", "account",  "fund", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex key \\[-hVv\\]", "key", "help");
		assertExecuteCommandLineResult(0, "^Usage: convex key generate \\[-hVv\\]", "key", "generate", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex key list \\[-hVv\\]", "key", "list", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex peer \\[-hVv\\]", "peer", "help");
		assertExecuteCommandLineResult(0, "^Usage: convex peer start \\[-hrVv\\]", "peer", "start", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex local \\[-hVv\\]", "local", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex local start \\[-hVv\\]", "local", "start", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex local gui \\[-hVv\\]", "local", "gui", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex query \\[-hVv\\]", "query", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex status \\[-hVv\\]", "status", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex transaction \\[-hVv\\]", "transaction", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex transaction \\[-hVv\\]", "transact", "--help");
	}

}
