package convex.cli.account;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.ACommand;
import convex.cli.CLIError;
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
		try {
			return peerMixin.connect();
		} catch (IOException e) {
			throw new CLIError("Unable to connect to Convex network",e);
		}	catch (TimeoutException e) {
			throw new CLIError("Timout connecting to Convex network",e);
		}
	}
	
	@Override
	public Main cli() {
		return accountParent.cli();
	}
}
