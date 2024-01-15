package convex.cli;

import picocli.CommandLine.ParentCommand;

public abstract class ATopCommand implements Runnable {

	@ParentCommand
	protected Main mainParent;

	public Main cli() {
		return mainParent;
	}
}
