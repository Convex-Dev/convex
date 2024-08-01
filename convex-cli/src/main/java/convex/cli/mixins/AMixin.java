package convex.cli.mixins;

import convex.cli.ACommand;
import convex.cli.Main;
import picocli.CommandLine.ParentCommand;

public class AMixin extends ACommand{

	@ParentCommand 
	ACommand parentCommand;

	@Override
	public void run() {
		throw new Error("Mixin should not be called as command!");
	}

	@Override
	public Main cli() {
		return parentCommand.cli();
	}

}
