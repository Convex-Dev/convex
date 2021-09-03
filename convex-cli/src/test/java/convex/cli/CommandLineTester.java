
package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import picocli.CommandLine;


public class CommandLineTester {
	protected String output;
	protected int result;
	protected String args[];

	public CommandLineTester(String ... args) {
		this.args = args;
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		Main mainApp = new Main();

		CommandLine commandLine = new CommandLine(mainApp);

		commandLine.setOut(printWriter);

		this.result = commandLine.execute(args);
		mainApp.output.writeToStream(printWriter);
		this.output = new String(outputWriter.toString());
	}

	public void assertOutputMatch(String patternText) {
		Pattern regex = Pattern.compile(patternText, Pattern.MULTILINE + Pattern.DOTALL);
		Matcher matcher = regex.matcher(output);

		String assertText = "\nCommand: convex " + String.join(" ", args) +
			"\nMatch: '" + patternText + "'" +
			"\nOutput: '" + output.substring(0, Math.min(132, output.length())) + "'" +
			"\n";
		assertEquals(true, matcher.find(),  assertText);
	}


	public String getField(String name) {
		String lines[] = output.split("\\r?\\n");
		for (int index = 0; index < lines.length; index ++) {
			String line = lines[index];
			int position = line.indexOf(name);
			if (position > 0) {
				return line.substring(position + name.length());
			}
		}
		return "";
	}

	public String getOutput() {
		return output;
	}

	public int getResult() {
		return result;
	}

	public String[] getArgs() {
		return args;
	}
}
