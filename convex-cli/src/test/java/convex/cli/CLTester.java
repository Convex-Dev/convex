
package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import picocli.CommandLine;


public class CLTester {
	protected String output;
	protected int result;
	protected String args[];

	private CLTester(String ... args) {
		this.args = args;
	}
	
	public static CLTester run(String... args) {
		CLTester tester=new CLTester(args);
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);

		Main mainApp = new Main();

		CommandLine commandLine = mainApp.commandLine;

		commandLine.setOut(printWriter);

		tester.result = commandLine.execute(args);
		printWriter.flush();
		tester.output = new String(outputWriter.toString());
		return tester;
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
