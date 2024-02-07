package convex.cli;

import picocli.CommandLine.ParentCommand;

public abstract class ATopCommand extends ACommand {

	@ParentCommand
	protected Main mainParent;

	@Override
	public Main cli() {
		return mainParent;
	}
}
