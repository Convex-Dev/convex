
package convex.cli;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.io.StringWriter;

import picocli.CommandLine;

public class CLTester {
	protected String output;
	protected String error;
	protected int result;
	protected String args[];

	private CLTester(String ... args) {
		this.args = args;
	}
	
	public static CLTester run(String... args) {
		CLTester tester=new CLTester(args);
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		StringWriter errWriter = new StringWriter();
		PrintWriter errPrintWriter = new PrintWriter(errWriter);

		Main mainApp = new Main();

		CommandLine commandLine = mainApp.commandLine;

		commandLine.setOut(printWriter);
		commandLine.setErr(errPrintWriter);

		tester.result = commandLine.execute(args);
		printWriter.flush();
		tester.output = new String(outputWriter.toString());
		tester.error = new String(errWriter.toString());
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

	public String getError() {
		return error;
	}

	public void assertExitCode(int expected) {
		if (result==expected) return;
		System.err.println("STDOUT: "+output);
		System.err.println("STDERR: "+error);
		fail("Unexpected CLI result, expected "+expected+" but got "+result+ " with error: "+error);
	}
}
