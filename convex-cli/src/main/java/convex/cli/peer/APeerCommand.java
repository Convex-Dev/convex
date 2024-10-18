package convex.cli.peer;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.EtchMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.PeerKeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.etch.EtchStore;
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
	 * Get the keypair for the peer. May return null if not specified or not available in store
	 */
	protected AKeyPair specifiedPeerKey() {
		String peerPublicKey=peerKeyMixin.getPublicKey();
		if (peerPublicKey==null) {
			paranoia("You must specify a --peer-key for the peer");
			return null;
		} else {
			AKeyPair result=storeMixin.loadKeyFromStore(peerPublicKey, ()->peerKeyMixin.getKeyPassword());
			return result;
		}
	}
	
	/**
	 * Get the keypair for the peer controller account. Returns null if not specified
	 */
	protected AKeyPair ensureControllerKey() {
		String controllerKey=keyMixin.getPublicKey();
		if (controllerKey==null) {
			paranoia("You must specify a --key for the peer controller");
			return null;
		}
		
		AKeyPair result=storeMixin.loadKeyFromStore(controllerKey, ()->keyMixin.getKeyPassword());
		return result;
	}
}
