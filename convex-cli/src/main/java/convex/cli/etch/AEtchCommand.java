package convex.cli.etch;

import convex.cli.ACommand;
import convex.cli.Main;
import etch.EtchStore;
import picocli.CommandLine.ParentCommand;

public abstract class AEtchCommand extends ACommand {
	
	@ParentCommand
	protected Etch etchParent;

	@Override
	public Main cli() {
		return etchParent.cli();
	}
	
	public EtchStore store() {
		return etchParent.store();
	}

}
