package convex.cli.client;

import convex.api.Convex;
import convex.cli.ATopCommand;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.cli.mixins.AddressMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.RemotePeerMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

public abstract class AClientCommand extends ATopCommand {
	
	@Mixin
	protected KeyStoreMixin storeMixin; 
	
	@Mixin
	protected KeyMixin keyMixin;

	@Mixin
	protected RemotePeerMixin peerMixin;
	
	@Mixin
	protected AddressMixin addressMixin;
	
	@Option(names={"--timeout"},
			description="Timeout in miliseconds.")
	private Long timeout;

	protected long getClientTimeout() {
		// Gets timeout specified at CLI, or default value
		return timeout==null?Constants.DEFAULT_TIMEOUT_MILLIS : timeout;
	}

	
	/**
	 * Connect as a client to the convex network
	 * @return
	 */
	protected Convex clientConnect() {
		Convex convex= peerMixin.connect();
		if (timeout!=null) {
			convex.setTimeout(timeout);
		}
		return convex;
	}
	
	/**
	 * Connect to Convex ready to query
	 * @return
	 */
	protected Convex connectQuery() {
		Convex convex=clientConnect();
		Address a=getUserAddress();
		convex.setAddress(a);
		return convex;
	}
	
	/**
	 * Connect to Convex ready to transact
	 * @return
	 * @throws InterruptedException 
	 */
	protected Convex connectTransact() throws InterruptedException {
		Convex convex=connectQuery();
		return convex;
	}
	
	/**
	 * Gets user address, prompting of not provided.
	 * @return Valid Address or null if Address not valid
	 */
	public Address getUserAddress() {
		return addressMixin.getAddress("Enter Convex user account address: ");	
	}
	
	
	protected void ensureKeyPair(Convex convex) throws InterruptedException {
		Address a=convex.getAddress();
		AKeyPair keyPair = convex.getKeyPair();
		if (keyPair!=null) return;

		String pk=keyMixin.getPublicKey();
		if (pk==null) {
			paranoia("You must set --key explicitly in strict security mode");
			
			AccountKey k=convex.getAccountKey(a);
			if (k!=null) {
				pk=k.toHexString();
				inform("Address "+a+" requires public key "+pk);
			} else if (isInteractive()) {
				pk=prompt("Enter public key for Address "+a+": ");
			} else {
				throw new CLIError(ExitCodes.USAGE,"Public key required.");
			}
		}
		
		storeMixin.ensureKeyStore();
		int c=storeMixin.keyCount(pk);
		if (c==0) {
			throw new CLIError(ExitCodes.CONFIG,"Can't find keypair with public key: "+pk);
		} else if (c>1) {
			throw new CLIError(ExitCodes.CONFIG,"Multiple key pairs found");
		}
		
		keyPair=storeMixin.loadKeyFromStore(pk,()->keyMixin.getKeyPassword());
		if (keyPair==null) {
			// We didn't find required keypair
			throw new CLIError(ExitCodes.CONFIG,"Can't find keypair with public key: "+pk);
		}
		convex.setKeyPair(keyPair);
	}



}
