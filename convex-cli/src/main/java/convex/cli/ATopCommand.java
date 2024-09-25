package convex.cli;

import picocli.CommandLine.ParentCommand;

/**
 * Abstract base class for top level subcommands, i.e. convex xxxx
 */
public abstract class ATopCommand extends ACommand {

	@ParentCommand
	protected Main mainParent;

	@Override
	public Main cli() {
		return mainParent;
	}
}
