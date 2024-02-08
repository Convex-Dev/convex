package convex.cli.etch;

import convex.core.data.ACell;
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
	private String data;

	@Override
	public void run() {
		
		if ((data==null)) {
			cli().inform("No data provided. Suggestion: use arg --cvx <data>");
			return;
		}
		
		EtchStore store=store();
		ACell cell=Reader.read(data);
		
		store.storeTopRef(Ref.get(cell), Ref.PERSISTED, null);
		
		cli().inform("Data saved with hash: "+Ref.get(cell).getHash());
	}
}
