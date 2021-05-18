package convex.cli;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.InterruptedException;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex Peer Manager sub command
*
*/
@Command(name="manager",
	mixinStandardHelpOptions=true,
	description="Launch the peer manager gui.")
public class PeerManager implements Runnable {

	private static final Logger log = Logger.getLogger(PeerManager.class.getName());

	@ParentCommand
	protected Peer peerParent;

	@Option(names={"-j", "--java"},
		defaultValue="java",
		description="Path to java runtime file. Default: ${DEFAULT-VALUE}")
	String javaPath;

	@Override
	public void run() {
		// sub command to launch peer manager
		try {
			Runtime runtime = Runtime.getRuntime();
			Process process = runtime.exec(javaPath + " -cp target/convex.jar convex.gui.manager.PeerManager");
			process.waitFor();
		} catch (IOException | InterruptedException e) {
			System.err.println("cannot start PeerManager "+e);
		}
	}
}
