package convex.cli.peer;

import java.util.List;

import convex.core.data.AccountKey;
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
		List<AccountKey> keys=etchMixin.getPeerList();
		for (AccountKey k: keys) {
			println(k.toHexString());
		}
	}
}
