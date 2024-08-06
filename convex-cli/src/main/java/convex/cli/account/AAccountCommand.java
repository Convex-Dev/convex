package convex.cli.account;

import convex.api.Convex;
import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.RemotePeerMixin;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class AAccountCommand extends ACommand {

	@ParentCommand
	Account accountParent;
	
	@Mixin
	protected RemotePeerMixin peerMixin;
	
	protected Convex connect() {
		return peerMixin.connect();
	}
	
	@Override
	public Main cli() {
		return accountParent.cli();
	}
}
