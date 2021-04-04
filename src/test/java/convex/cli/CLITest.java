package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

public class CLITest {
	
	private static final String OS = System.getProperty("os.name").toLowerCase();
	
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
		Main.main("--help");

	}
	
	@Test
	public void testHelp() {
		Process p;
		
		p=runCLI("convex --help");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());
		
		p=runCLI("convex --help key");
		p=awaitExit(p);
		assertEquals(0,p.exitValue());

	}


}
