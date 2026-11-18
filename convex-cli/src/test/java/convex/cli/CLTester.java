
package convex.cli;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import picocli.CommandLine;

public class CLTester implements AutoCloseable {
	private static final Duration COMPLETION_TIMEOUT=Duration.ofSeconds(30);
	private static final Duration SHUTDOWN_TIMEOUT=Duration.ofSeconds(5);
	private volatile Throwable failure;
	protected String output;
	protected String error;
	protected int result;
	protected String args[];
	protected Thread thread;
	Main mainApp=new Main();

	CLTester(String ... args) {
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
	 * Run a CLI instance with the given standard input, e.g. for commands that
	 * read piped input. Callers must hold the JUnit resource lock
	 * {@code java.lang.System.in} while replacing this process-wide stream.
	 * @param input Standard input for the command
	 * @param args CLI arguments
	 * @return CLTester containing the output
	 */
	public static CLTester runWithInput(String input, String... args) {
		InputStream oldIn=System.in;
		try {
			System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
			return run(args);
		} finally {
			System.setIn(oldIn);
		}
	}

	/**
	 * Run a CLI instance asynchronously.
	 * @param args CLI arguments
	 * @return CLTester to receive the output
	 */
	public static CLTester runAsync(String... args) {
		CLTester tester=new CLTester(args);
		tester.startAsync();
		return tester;
	}

	void startAsync() {
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		StringWriter errWriter = new StringWriter();
		PrintWriter errPrintWriter = new PrintWriter(errWriter);

		CommandLine commandLine = mainApp.commandLine;

		commandLine.setOut(printWriter);
		commandLine.setErr(errPrintWriter);

		thread=new Thread(()->{
			try {
				result = commandLine.execute(args);
			} catch (Throwable t) {
				failure=t;
				mainApp.startupFuture.completeExceptionally(t);
			} finally {
				printWriter.flush();
				errPrintWriter.flush();
				output = outputWriter.toString();
				error = errWriter.toString();
			}
		},"cli-test-command");
		// A command ignoring cancellation must fail the test, not prevent JVM exit.
		thread.setDaemon(true);
		thread.start();
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
		awaitCompletion(SHUTDOWN_TIMEOUT);
	}

	/**
	 * Waits for test to complete if in async mode
	 */
	public void ensureComplete() {
		awaitCompletion(COMPLETION_TIMEOUT);
	}

	void awaitCompletion(Duration timeout) {
		if (thread==null) return;
		try {
			if (!thread.join(timeout)) {
				thread.interrupt();
				boolean stopped=thread.join(SHUTDOWN_TIMEOUT);
				fail("CLI command timed out after "+timeout+"; "
						+(stopped?"worker cancelled":"worker did not stop after cancellation"));
			}
		} catch (InterruptedException e) {
			thread.interrupt();
			try {
				if (!thread.join(SHUTDOWN_TIMEOUT)) e.addSuppressed(new IllegalStateException("CLI worker did not stop"));
			} catch (InterruptedException cleanup) {
				e.addSuppressed(cleanup);
			} finally {
				Thread.currentThread().interrupt();
			}
			fail("Interrupted while waiting for CLI command",e);
		}
		if (failure!=null) fail("CLI worker failed",failure);
	}

	@Override
	public void close() {
		interrupt();
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
		fail("Unexpected CLI result, expected "+expected+" but got "+result
				+"\nSTDOUT: "+output+"\nSTDERR: "+error);
	}


}
