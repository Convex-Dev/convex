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
		assertCommandLineResult(0, "^Usage: convex \\[-hV\\] .*", "--help");
		assertCommandLineResult(0, "^Usage: convex \\[-hV\\] .*", "-h");
		assertCommandLineResult(0, "^Usage: convex \\[-hV\\] .*", "help");
		assertCommandLineResult(0, "^Usage: convex key \\[-hV\\] .*", "key", "help");
		assertCommandLineResult(0, "^Usage: convex key generate \\[-hV\\] .*", "key", "generate", "--help");
		assertCommandLineResult(0, "^Usage: convex key list \\[-hV\\] .*", "key", "list", "--help");
		assertCommandLineResult(0, "^Usage: convex peer \\[-hV\\] .*", "peer", "help");
		assertCommandLineResult(0, "^Usage: convex peer start \\[-hrV\\] .*", "peer", "start", "--help");
		assertCommandLineResult(0, "^Usage: convex local \\[-hV\\] .*", "local", "--help");
		assertCommandLineResult(0, "^Usage: convex local start \\[-hV\\] .*", "local", "start", "--help");
		assertCommandLineResult(0, "^Usage: convex local manager \\[-hV\\] .*", "local", "manager", "--help");
		assertCommandLineResult(0, "^Usage: convex query \\[-hV\\] .*", "query", "--help");
		assertCommandLineResult(0, "^Usage: convex status \\[-hV\\] .*", "status", "--help");
		assertCommandLineResult(0, "^Usage: convex transaction \\[-hV\\] .*", "transaction", "--help");
		assertCommandLineResult(0, "^Usage: convex transaction \\[-hV\\] .*", "transact", "--help");
	}


}
