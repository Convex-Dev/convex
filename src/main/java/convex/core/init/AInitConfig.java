package convex.core.init;


import convex.core.crypto.AKeyPair;
import convex.core.data.Address;


public abstract class AInitConfig {

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
