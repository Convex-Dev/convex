package convex.api;

import convex.core.crypto.AKeyPair;
import convex.core.data.Address;

public class ConvexRemote extends Convex {

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}

}
