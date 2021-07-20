package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;


public class CLITest {

	protected static final String OS = System.getProperty("os.name").toLowerCase();
	protected static final Logger log = LoggerFactory.getLogger(CLITest.class.getName());

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
		assertCommandLineResult(0, "^Usage: convex \\[-hVv\\] .*", "--help");
		assertCommandLineResult(0, "^Usage: convex \\[-hVv\\] .*", "-h");
		assertCommandLineResult(0, "^Usage: convex \\[-hVv\\] .*", "help");
		assertCommandLineResult(0, "^Usage: convex account \\[-hVv\\] .*", "account", "help");
		assertCommandLineResult(0, "^Usage: convex account balance \\[-hVv\\] .*", "account",  "balance", "--help");
		assertCommandLineResult(0, "^Usage: convex account create \\[-fhVv\\] .*", "account",  "create", "--help");
		assertCommandLineResult(0, "^Usage: convex account information \\[-hVv\\] .*", "account",  "information", "--help");
		assertCommandLineResult(0, "^Usage: convex account fund \\[-hVv\\] .*", "account",  "fund", "--help");
		assertCommandLineResult(0, "^Usage: convex key \\[-hVv\\] .*", "key", "help");
		assertCommandLineResult(0, "^Usage: convex key generate \\[-hVv\\] .*", "key", "generate", "--help");
		assertCommandLineResult(0, "^Usage: convex key list \\[-hVv\\] .*", "key", "list", "--help");
		assertCommandLineResult(0, "^Usage: convex peer \\[-hVv\\] .*", "peer", "help");
		assertCommandLineResult(0, "^Usage: convex peer start \\[-hrVv\\] .*", "peer", "start", "--help");
		assertCommandLineResult(0, "^Usage: convex local \\[-hVv\\] .*", "local", "--help");
		assertCommandLineResult(0, "^Usage: convex local start \\[-hVv\\] .*", "local", "start", "--help");
		assertCommandLineResult(0, "^Usage: convex local gui \\[-hVv\\] .*", "local", "gui", "--help");
		assertCommandLineResult(0, "^Usage: convex query \\[-hVv\\] .*", "query", "--help");
		assertCommandLineResult(0, "^Usage: convex status \\[-hVv\\] .*", "status", "--help");
		assertCommandLineResult(0, "^Usage: convex transaction \\[-hVv\\] .*", "transaction", "--help");
		assertCommandLineResult(0, "^Usage: convex transaction \\[-hVv\\] .*", "transact", "--help");
	}


}
