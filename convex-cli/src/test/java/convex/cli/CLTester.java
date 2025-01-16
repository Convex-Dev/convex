
package convex.cli;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import picocli.CommandLine;

public class CLTester {
	protected String output;
	protected String error;
	protected int result;
	protected String args[];
	protected Thread thread;
	Main mainApp=new Main();

	private CLTester(String ... args) {
		this.args = args;
	}
	
	/** 
	 * Run a CLI instance
	 * @param args CLI arguments
	 * @return CLTester containing the output
	 */
	public static CLTester run(String... args) {
		CLTester tester=new CLTester(args);
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		StringWriter errWriter = new StringWriter();
		PrintWriter errPrintWriter = new PrintWriter(errWriter);

		CommandLine commandLine = tester.mainApp.commandLine;
		commandLine.setOut(printWriter);
		commandLine.setErr(errPrintWriter);

		tester.result = tester.mainApp.mainExecute(args);
		printWriter.flush();
		tester.output = new String(outputWriter.toString());
		tester.error = new String(errWriter.toString());
		return tester;
	}
	
	/** 
	 * Run a CLI instance asynchronously. 
	 * @param args CLI arguments
	 * @return CLTester to receive the output
	 */
	public static CLTester runAsync(String... args) {
		CLTester tester=new CLTester(args);
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		StringWriter errWriter = new StringWriter();
		PrintWriter errPrintWriter = new PrintWriter(errWriter);

		CommandLine commandLine = tester.mainApp.commandLine;

		commandLine.setOut(printWriter);
		commandLine.setErr(errPrintWriter);

		tester.thread=new Thread(()->{
			tester.result = commandLine.execute(args);
			printWriter.flush();
			errPrintWriter.flush();
			tester.output = new String(outputWriter.toString());
			tester.error = new String(errWriter.toString());
		});
		tester.thread.start();
		return tester;
	}
	
	public String waitForStart(int timeout) throws InterruptedException, ExecutionException, TimeoutException {
		return mainApp.startupFuture.get(timeout,TimeUnit.MILLISECONDS);
	}

	public String getOutput() {
		ensureComplete();
		return output;
	}
	
	/**
	 * Interrupts the tester if running in async mode
	 */
	public void interrupt() {
		if (thread==null) return;
		thread.interrupt();
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Waits for test to complete if in async mode
	 */
	public void ensureComplete() {
		if (thread==null) return;
		try {
			thread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public int getResult() {
		ensureComplete();
		return result;
	}

	public String[] getArgs() {
		return args;
	}

	public String getError() {
		ensureComplete();
		return error;
	}

	public void assertExitCode(int expected) {
		ensureComplete();
		if (result==expected) return;
		System.err.println("STDOUT: "+output);
		System.err.println("STDERR: "+error);
		fail("Unexpected CLI result, expected "+expected+" but got "+result+ " with error: "+error);
	}


}
