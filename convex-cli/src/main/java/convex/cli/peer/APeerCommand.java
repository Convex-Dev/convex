package convex.cli.peer;

import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.EtchMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.PeerKeyMixin;
import convex.cli.mixins.StoreMixin;
import convex.core.crypto.AKeyPair;
import etch.EtchStore;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class APeerCommand extends ACommand {

	@Mixin
    protected EtchMixin etchMixin;
	
	@Mixin
    protected KeyMixin keyMixin;
	
	@Mixin
	protected PeerKeyMixin peerKeyMixin;
	
	@Mixin
	protected StoreMixin storeMixin; 
	

	@ParentCommand
	private ACommand parent;
	
	@Override
	public Main cli() {
		return parent.cli();
	}

	public EtchStore getEtchStore() {
		return etchMixin.getEtchStore();
	}
	
	/**
	 * Get the keypair for the peer
	 */
	protected AKeyPair ensurePeerKey() {
		String peerPublicKey=peerKeyMixin.getPublicKey();
		if (peerPublicKey==null) {
			if (!isInteractive()) {
				throw new CLIError(ExitCodes.USAGE,"--peer-key must be specified in non-interactive mode");
			}
		}
		
		char[] keyPass=peerKeyMixin.getKeyPassword();
		
		AKeyPair result=storeMixin.loadKeyFromStore(peerPublicKey, keyPass);
		return result;
	}
	
	/**
	 * Get the keypair for the peer controller account
	 */
	protected AKeyPair ensureControllerKey() {
		String peerPublicKey=keyMixin.getPublicKey();
		if (peerPublicKey==null) {
			if (!isInteractive()) {
				throw new CLIError(ExitCodes.USAGE,"Controller --key must be specified in non-interactive mode");
			}
		}
		
		char[] keyPass=keyMixin.getKeyPassword();
		
		AKeyPair result=storeMixin.loadKeyFromStore(peerPublicKey, keyPass);
		return result;
	}
}
