package convex.cli.etch;

import convex.cli.Main;
import etch.EtchStore;
import picocli.CommandLine.ParentCommand;

public abstract class AEtchCommand implements Runnable{
	
	@ParentCommand
	protected Etch etchParent;

	protected Main cli() {
		return etchParent.cli();
	}
	
	public EtchStore store() {
		return etchParent.store();
	}

}
