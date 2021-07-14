package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;


public class CLITest {

	protected static final String OS = System.getProperty("os.name").toLowerCase();
	protected static final Logger log = Logger.getLogger(CLITest.class.getName());

	private void assertMatch(String patternText, StringWriter output, String[] args) {
		Pattern regex = Pattern.compile(patternText, Pattern.MULTILINE + Pattern.DOTALL);
		String outputText = output.toString();
		Matcher matcher = regex.matcher(outputText);
		String assertText = "convex " + String.join(" ", args) + " == '" + patternText + "'"
			+ "\n But got ...\n" + outputText.substring(0, Math.min(132, outputText.length()));
		assertEquals(true, matcher.matches(),  assertText);
	}

	private void assertCommandLineResult(int returnCode, String patternText, String ... args) {
		StringWriter output = new StringWriter();
		PrintWriter printWriter = new PrintWriter(output);
		Main mainApp = new Main();

		CommandLine commandLine = new CommandLine(mainApp);

		commandLine.setOut(printWriter);
		int result = commandLine.execute(args);
		assertEquals(returnCode, result);
		assertMatch(patternText, output, args);
	}

	@Test
	public void testHelp() {
		assertCommandLineResult(0, "^Usage: convex \\[-hvV\\] .*", "--help");
		assertCommandLineResult(0, "^Usage: convex \\[-hvV\\] .*", "-h");
		assertCommandLineResult(0, "^Usage: convex \\[-hvV\\] .*", "help");
		assertCommandLineResult(0, "^Usage: convex account \\[-hvV\\] .*", "account", "help");
		assertCommandLineResult(0, "^Usage: convex account balance \\[-hvV\\] .*", "account",  "balance", "--help");
		assertCommandLineResult(0, "^Usage: convex account create \\[-fhvV\\] .*", "account",  "create", "--help");
		assertCommandLineResult(0, "^Usage: convex account information \\[-hvV\\] .*", "account",  "information", "--help");
		assertCommandLineResult(0, "^Usage: convex account fund \\[-hvV\\] .*", "account",  "fund", "--help");
		assertCommandLineResult(0, "^Usage: convex key \\[-hvV\\] .*", "key", "help");
		assertCommandLineResult(0, "^Usage: convex key generate \\[-hvV\\] .*", "key", "generate", "--help");
		assertCommandLineResult(0, "^Usage: convex key list \\[-hvV\\] .*", "key", "list", "--help");
		assertCommandLineResult(0, "^Usage: convex peer \\[-hvV\\] .*", "peer", "help");
		assertCommandLineResult(0, "^Usage: convex peer start \\[-hrvV\\] .*", "peer", "start", "--help");
		assertCommandLineResult(0, "^Usage: convex local \\[-hvV\\] .*", "local", "--help");
		assertCommandLineResult(0, "^Usage: convex local start \\[-hvV\\] .*", "local", "start", "--help");
		assertCommandLineResult(0, "^Usage: convex local gui \\[-hvV\\] .*", "local", "gui", "--help");
		assertCommandLineResult(0, "^Usage: convex query \\[-hvV\\] .*", "query", "--help");
		assertCommandLineResult(0, "^Usage: convex status \\[-hvV\\] .*", "status", "--help");
		assertCommandLineResult(0, "^Usage: convex transaction \\[-hvV\\] .*", "transaction", "--help");
		assertCommandLineResult(0, "^Usage: convex transaction \\[-hvV\\] .*", "transact", "--help");
	}


}
