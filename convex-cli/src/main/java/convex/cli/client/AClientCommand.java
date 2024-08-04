package convex.cli.client;

import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.cli.ATopCommand;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.mixins.AddressMixin;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.RemotePeerMixin;
import convex.cli.mixins.StoreMixin;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Special;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

public abstract class AClientCommand extends ATopCommand {
	
	@Mixin
	protected StoreMixin storeMixin; 
	
	@Mixin
	protected KeyMixin keyMixin;

	@Mixin
	protected RemotePeerMixin peerMixin;
	
	@Mixin
	protected AddressMixin addressMixin;
	
	@Option(names={"--timeout"},
			description="Timeout in miliseconds.")
	protected long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;


	/**
	 * Connect as a client to the convex network
	 * @return
	 */
	protected Convex clientConnect() {
		try {
		return peerMixin.connect();
		} catch (Exception ex) {
			throw new CLIError("Unable to connect to Convex: "+ex.getMessage(),ex);
		}
	}
	
	
	public Address getUserAddress() {
		Address result= addressMixin.getAddress("Enter Convex account address: ");	
		return result;
	}
	
	protected boolean ensureAddress(Convex convex) {
		Address a = getUserAddress();
		if (a!=null) {
			convex.setAddress(a);
			return true;
		}
		return false;
	}
	
	protected boolean ensureKeyPair(Convex convex) {
		AKeyPair keyPair = convex.getKeyPair();
		if (keyPair!=null) return true;

		Address address=convex.getAddress();
		
		// Try to identify the required keypair for the Address
		Result ar;
		try {
			ar = convex.query(Special.forSymbol(Symbols.STAR_KEY)).get(1000,TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			ar=null;
		}
		
		String pk=null;
		if (ar==null) {
			// we couldn't query the *key*, so prompt the user
		} else if (!ar.isError()) {
			// Attempt to use query result as public key
			ACell v=ar.getValue();
			if (v instanceof ABlob) {
				pk=((ABlob)v).toHexString();
			}
		}
		
		if (pk==null) {
			pk=prompt("Enter public key: ");
		}
		
		keyPair=storeMixin.loadKeyFromStore(pk,keyMixin.getKeyPassword());
		if (keyPair==null) {
			// We didn't find required keypair
			throw new CLIError("Can't find keypair with public key "+pk+" for Address "+address);
		}
		convex.setKeyPair(keyPair);
		return true;
	}


}
