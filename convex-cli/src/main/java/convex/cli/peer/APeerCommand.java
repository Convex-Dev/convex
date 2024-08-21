package convex.cli.peer;

import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.EtchMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.PeerKeyMixin;
import convex.cli.mixins.KeyStoreMixin;
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
	protected KeyStoreMixin storeMixin; 
	

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
	 * Get the keypair for the peer. May return null if not specified
	 */
	protected AKeyPair checkPeerKey() {
		String peerPublicKey=peerKeyMixin.getPublicKey();
		if (peerPublicKey==null) {
			paranoia("You must specify a --peer-key for the peer");
			if (!isInteractive()) {
				throw new CLIError(ExitCodes.USAGE,"--peer-key must be specified in non-interactive mode");
			} else {
				boolean shouldGenerate=question("No --peer-key specified. Generate one? (y/n)");
				if (shouldGenerate) {
					AKeyPair kp=AKeyPair.generate();
					inform("Generated peer key: "+kp.getAccountKey().toChecksumHex());
					inform("Generated peer seed: "+kp.getSeed());
					char[] keyPass=peerKeyMixin.getKeyPassword();
					storeMixin.addKeyPairToStore(kp, keyPass);
					storeMixin.saveKeyStore();
					return kp;
				} else {
					throw new CLIError("Operation cancelled");
				}
			}
		} else {
			char[] keyPass=peerKeyMixin.getKeyPassword();
			AKeyPair result=storeMixin.loadKeyFromStore(peerPublicKey, keyPass);
			if (result==null) throw new CLIError("Peer key not found in store");
			return result;
		}
	}
	
	/**
	 * Get the keypair for the peer controller account
	 */
	protected AKeyPair ensureControllerKey() {
		String controllerKey=keyMixin.getPublicKey();
		if (controllerKey==null) {
			paranoia("You must specify a --key for the peer controller");
			return null;
		}
		
		char[] keyPass=keyMixin.getKeyPassword();
		
		AKeyPair result=storeMixin.loadKeyFromStore(controllerKey, keyPass);
		if (result==null) throw new CLIError("Peer controller key not found in store");
		return result;
	}
}
