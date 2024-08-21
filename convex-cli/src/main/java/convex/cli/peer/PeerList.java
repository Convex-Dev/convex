package convex.cli.peer;

import java.io.IOException;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 * Peer genesis command
 */
@Command(
	name = "list",
	mixinStandardHelpOptions = true, 
	description = "List peers in current store.")
public class PeerList extends APeerCommand {

	@ParentCommand
	private Peer peerParent;


	@Override
	public void run() {
		
		EtchStore etch=etchMixin.getEtchStore();
		try {
			AMap<ACell,ACell> rootData=etch.getRootData();
			if (rootData==null) {
				informWarning("No root data in store "+etch);
				return;
			}
			
			long n=rootData.count();
			for (long i=0; i<n; i++) {
				println(rootData.entryAt(i).getKey());
			}
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"IO Error reating etch store at "+etch,e);
		} finally {
			etch.close();
		}
	}
}
