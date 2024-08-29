package convex.cli.peer;

import java.io.IOException;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.data.AccountKey;
import convex.etch.EtchStore;
import convex.peer.API;
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
	public void execute() {
		
		EtchStore etch=etchMixin.getEtchStore();
		try {
			List<AccountKey> keys=API.listPeers(etch);
			for (AccountKey k: keys) {
				println(k.toHexString());
			}
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"IO Error reating etch store at "+etch,e);
		} finally {
			etch.close();
		}
	}
}
