package convex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Option;

/**
*
* Convex key sub commands
*
*/
@Command(name="key",
	subcommands = {
		KeyGenerate.class,
		KeyList.class,
		CommandLine.HelpCommand.class
	},
	mixinStandardHelpOptions=true,
	description="Manage local Convex key store.")
public class Key implements Runnable {

	@ParentCommand
	protected Main mainParent;

	@Option(names={"-k", "--keystore"},
		defaultValue=Constants.KEYSTORE_FILENAME,
		description="keystore filename. Default: ${DEFAULT-VALUE}")
	private String keyStoreFilename;

	@Option(names={"-p", "--password"},
		description="Password to read/write to the Keystore")
	private String password;

	@Override
	public void run() {
		// sub command run with no command provided
		CommandLine.usage(new Key(), System.out);
	}

	public String getPassword() {
		return password;
	}

	public String getKeyStoreFilename() {
		return Helpers.expandTilde(keyStoreFilename);
	}
}
