package convex.core.init;

import java.io.IOException;
import java.util.logging.Logger;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;


public abstract class AInitConfig {

	private static final Logger log = Logger.getLogger(AInitConfig.class.getName());

	protected AKeyPair userKeyPairs[];
	protected AKeyPair peerKeyPairs[];


	public int getUserCount() {
		return userKeyPairs.length;
	}
	public int getPeerCount() {
		return peerKeyPairs.length;
	}

	public AKeyPair getUserKeyPair(int index) {
		return userKeyPairs[index];
	}
	public AKeyPair getPeerKeyPair(int index) {
		return peerKeyPairs[index];
	}

	public AKeyPair[] getPeerKeyPairs() {
		return peerKeyPairs;
    }

	public Address getUserAddress(int index) {
		return Init.calcAddress(0, 0, index);
	}

	public Address getPeerAddress(int index) {
		return Init.calcAddress(getUserCount(), 0, index);
	}

	public Address[] getPeerAddressList() {
		Address result[] = new Address[getPeerCount()];
		for (int index = 0; index < getPeerCount(); index ++) {
			result[index] = getPeerAddress(index);
		}
		return result;
	}

	public Address getLibraryAddress(int index) {
		return Init.calcAddress(getUserCount(), getPeerCount(), index);
	}

}
