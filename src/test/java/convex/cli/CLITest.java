package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

public class CLITest {

	private static final String OS = System.getProperty("os.name").toLowerCase();
	private static final Logger log = Logger.getLogger(CLITest.class.getName());

	private static final long TIMEOUT = 3000;

	public Process runCLI(String command) {
		if (OS.contains("win")) {
			try {
				Runtime rt = Runtime.getRuntime();
				Process pr = rt.exec("cmd /c "+command);
				return pr;
			} catch (IOException e) {
				throw Utils.sneakyThrow(e);
			}
		} else if (OS.contains("linux")) {
			try {
				Runtime rt = Runtime.getRuntime();
				Process pr = rt.exec(command.replaceAll("^convex", "./convex.sh"));
				return pr;
			} catch (IOException e) {
				throw Utils.sneakyThrow(e);
			}
		} else {
			assumeTrue(false);
			return null;
		}
	}

	private Process awaitExit(Process p) {
		try {
			Process result = p.onExit().get(TIMEOUT,TimeUnit.MILLISECONDS);
			return result;
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Test
	public void testMain() {
		// TODO: Test main, need to catch System.exit(...)
		// Main.main("--help");
		log.warning("os name " + OS);
	}

	@Test
	public void testBadCommand() {
		// assumeTrue(false);
		Process p;

		p=runCLI("convex foo");
		p=awaitExit(p);
		assertNotEquals(0,p.exitValue());
	}

	@Test
	public void testHelp() {
		// assumeTrue(false);
		Process p;

		p=runCLI("convex --help");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());

		p=runCLI("convex help");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());

		p=runCLI("convex key help");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());

		p=runCLI("convex peer help");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());

		p=runCLI("convex query help");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());

}


}
