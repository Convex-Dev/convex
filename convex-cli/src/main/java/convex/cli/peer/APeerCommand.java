package convex.cli.peer;

import java.util.HashMap;

import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.EtchMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.PeerKeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import convex.peer.Config;
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

	private AKeyPair configuredPeerKey;
	private AKeyPair hintedEtchKey;

	@Override
	public Main cli() {
		return parent.cli();
	}

	/**
	 * Gets the parent `peer` command group, or null if not applicable
	 */
	protected Peer peerGroup() {
		return (parent instanceof Peer)?((Peer)parent):null;
	}

	/** Loads the parent peer configuration with lazy keystore-backed Etch keys. */
	HashMap<Keyword,Object> loadPeerConfig() {
		Peer group=peerGroup();
		HashMap<Keyword,Object> result=(group==null)?new HashMap<>():group.loadPeerConfig(this::resolveEtchKey);
		Object configured=result.get(Keywords.KEYPAIR);
		if (configured instanceof AKeyPair keyPair) configuredPeerKey=keyPair;

		String keySpec=peerKeyMixin.getPublicKey();
		if (keySpec!=null) {
			AKeyPair keyPair=storeMixin.loadKeyFromStore(keySpec,peerKeyMixin::getKeyPassword);
			if (keyPair==null) {
				throw new CLIError(ExitCodes.CONFIG,"Peer key not found in keystore: "+keySpec);
			}
			configuredPeerKey=keyPair;
			result.put(Keywords.KEYPAIR,keyPair);
		}
		return result;
	}

	/** Installs a known launch identity and uses it as the default creation hint. */
	protected void setConfiguredPeerKey(HashMap<Keyword,Object> config, AKeyPair keyPair) {
		configuredPeerKey=keyPair;
		config.put(Keywords.KEYPAIR,keyPair);
	}

	protected AKeyPair getHintedEtchKey() {
		return hintedEtchKey;
	}

	/**
	 * Opens the configured peer store. Explicit {@code --etch} wins over
	 * {@code peer.store}; without either, the normal Etch default is retained.
	 */
	AStore openPeerStore(HashMap<Keyword,Object> config) {
		EtchConfig requested=(EtchConfig)config.get(Keywords.ETCH_CONFIG);
		EtchConfig effective;
		try {
			effective=Config.getEtchConfig(config);
		} catch (java.io.IOException e) {
			throw new CLIError(ExitCodes.CONFIG,"Invalid Etch runtime configuration: "+e.getMessage(),e);
		}
		Object configuredStore=config.get(Keywords.STORE);
		if (!etchMixin.isEtchFileSpecified()&&(configuredStore!=null)) {
			String storeName=configuredStore.toString();
			if ("memory".equals(storeName)) {
				if (requested!=null) {
					throw new CLIError(ExitCodes.CONFIG,"peer.etch cannot configure an in-memory store");
				}
				return new MemoryStore();
			}
			return warnIfPolicyDiffers(requested,etchMixin.getEtchStore(storeName,effective));
		}
		return warnIfPolicyDiffers(requested,etchMixin.getEtchStore(effective));
	}

	private EtchStore warnIfPolicyDiffers(EtchConfig requested, EtchStore store) {
		if ((requested==null)||sameFilePolicy(requested,store.getEtch().getConfig())) return store;
		EtchConfig actual=store.getEtch().getConfig();
		informWarning("Existing Etch store uses "+filePolicy(actual)
				+"; requested peer.etch creation policy "+filePolicy(requested)
				+" applies only to new stores.");
		return store;
	}

	private static boolean sameFilePolicy(EtchConfig a, EtchConfig b) {
		return (a.getVersion()==b.getVersion())
				&&(a.getCipherMode()==b.getCipherMode())
				&&(a.isIndexEncrypted()==b.isIndexEncrypted())
				&&((a.getPublicKeyHint()==null)
						||java.util.Objects.equals(a.getPublicKeyHint(),b.getPublicKeyHint()));
	}

	private static String filePolicy(EtchConfig config) {
		return "v"+config.getVersion()+", cipher="+config.getCipherMode().configName()
				+", encryptedIndex="+config.isIndexEncrypted()
				+", publicKeyHint="+config.getPublicKeyHint();
	}

	private byte[] resolveEtchKey(AccountKey publicKeyHint) {
		AKeyPair available=configuredPeerKey;
		if ((available!=null)&&((publicKeyHint==null)
				||publicKeyHint.equals(available.getAccountKey()))) {
			return Config.deriveEtchMasterKey(available);
		}
		String keySpec=(publicKeyHint==null)?peerKeyMixin.getPublicKey():publicKeyHint.toHexString();
		if (keySpec==null) {
			throw new CLIError(ExitCodes.CONFIG,"Encrypted Etch has no publicKeyHint; specify --peer-key");
		}
		AKeyPair keyPair=storeMixin.loadKeyFromStore(keySpec,peerKeyMixin::getKeyPassword);
		if (keyPair==null) {
			throw new CLIError(ExitCodes.CONFIG,"Etch encryption key not found in keystore: "+keySpec);
		}
		if (publicKeyHint!=null) hintedEtchKey=keyPair;
		return Config.deriveEtchMasterKey(keyPair);
	}

	public EtchStore getEtchStore() {
		return etchMixin.getEtchStore();
	}

	/** Opens the peer store using caller-resolved Etch configuration. */
	public EtchStore getEtchStore(EtchConfig config) {
		return etchMixin.getEtchStore(config);
	}
	
	/**
	 * Get the keypair for the peer. May return null if not specified or not available in store
	 */
	protected AKeyPair specifiedPeerKey() {
		String peerPublicKey=peerKeyMixin.getPublicKey();
		if (peerPublicKey==null) {
			paranoia("You must specify a --peer-key for the peer");
			return null;
		}
		if (configuredPeerKey!=null) return configuredPeerKey;
		return storeMixin.loadKeyFromStore(peerPublicKey,peerKeyMixin::getKeyPassword);
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
