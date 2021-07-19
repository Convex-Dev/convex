package convex.core.init;


import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.Address;


public class AInitConfig {

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
		return Init.calcUserAddress(index);
	}

	public Address getPeerAddress(int index) {
		return Init.calcPeerAddress(getUserCount(), index);
	}

	public Address[] getPeerAddressList() {
		Address result[] = new Address[getPeerCount()];
		for (int index = 0; index < getPeerCount(); index ++) {
			result[index] = getPeerAddress(index);
		}
		return result;
	}
	
	public static int DEFAULT_PEER_COUNT = 8;
	public static int DEFAULT_USER_COUNT = 2;


	protected AInitConfig(AKeyPair userKeyPairs[], AKeyPair peerKeyPairs[]) {
		this.userKeyPairs = userKeyPairs;
		this.peerKeyPairs = peerKeyPairs;
	}

	public static AInitConfig create() {
		return create(DEFAULT_USER_COUNT, DEFAULT_PEER_COUNT);
	}

	public static AInitConfig create(int userCount, int peerCount) {
		AKeyPair userKeyPairs[] = new Ed25519KeyPair[userCount];
		AKeyPair peerKeyPairs[] = new Ed25519KeyPair[peerCount];

		for (int i = 0; i < userCount; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(543212345 + i);
			userKeyPairs[i] = kp;
		}

		for (int i = 0; i < peerCount; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(123454321 + i);
			peerKeyPairs[i] = kp;
		}

		return new AInitConfig(userKeyPairs, peerKeyPairs);
	}

}
