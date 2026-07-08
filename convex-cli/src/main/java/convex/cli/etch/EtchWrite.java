package convex.cli.etch;

import java.io.IOException;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.lang.Reader;
import convex.etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="write",
mixinStandardHelpOptions=true,
description="Writes data values to the Etch store.")
public class EtchWrite extends AEtchCommand{
	
	@Option(names={"-c","--cvx"},
			description="Convex data in readable format.")
	private String cvxData;

	@Override
	public void execute() {
		
		if ((cvxData==null)) {
			cli().inform("No data provided. Suggestion: use arg --cvx <data>");
			return;
		}
		
		ACell cell;
		try {
			cell=Reader.read(cvxData);
		} catch (RuntimeException e) {
			throw new CLIError(ExitCodes.DATAERR,"Unable to parse --cvx data: "+e.getMessage(),e);
		}

		try (EtchStore store=store()) {
			Ref<ACell> ref=Ref.get(cell);
			store.storeTopRef(ref, Ref.PERSISTED, null);

			Hash h=ref.getHash();
			println(h.toString());
			informSuccess("Data saved with hash: "+h);
		} catch (IOException e) {
			throw new CLIError("Unable to write to store",e);
		}
	}
}
