package convex.cli.etch;

import java.io.IOException;

import convex.cli.CLIError;
import convex.etch.EtchStore;
import picocli.CommandLine.Command;

@Command(name="clear",
mixinStandardHelpOptions=true,
description="Clears the etch root data. Does NOT collect garbage.")
public class EtchClear extends AEtchCommand{

	@Override
	public void execute() {
		try {
			EtchStore store=store();
			store.setRootData(null);
			informSuccess("Etch data cleared in: "+store);
		} catch (IOException e) {
			throw new CLIError("IO Error accessing Etch database: "+e.getMessage());
		}
	}
}
