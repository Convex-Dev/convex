package convex.cli.etch;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.lang.Reader;
import etch.EtchStore;
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
	public void run() {
		
		if ((cvxData==null)) {
			cli().inform("No data provided. Suggestion: use arg --cvx <data>");
			return;
		}
		
		EtchStore store=store();
		try {
			ACell cell=Reader.read(cvxData);
			
			store.storeTopRef(Ref.get(cell), Ref.PERSISTED, null);
			
			Hash h=Ref.get(cell).getHash();
			println(h.toString());
			informSuccess("Data saved with hash: "+h);
		} finally {
			store.close();
		}
	}
}
