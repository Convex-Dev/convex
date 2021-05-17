package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
*
* Convex key sub commands
*
*/
@Command(name="key",
	mixinStandardHelpOptions=true,
	description="Manage local Convex key store.")
public class Key implements Runnable {

	@ParentCommand
	protected Main mainParent;

	// key generate command
	@Command(name="generate",
		aliases={"g","gen"},
		mixinStandardHelpOptions=true,
		description="Generate a new private key pair.")
	void generate() {
		System.out.println("key generate");
	}

	// key list command
	@Command(name="list",
		aliases={"l","li"},
		mixinStandardHelpOptions=true,
		description="List available key pairs.")
	void list() {
		System.out.println("key list");
	}

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Key(), System.out);
	}

}
