package convex.core;

import java.io.IOException;
import java.util.logging.Logger;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
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

public class InitConfigTest  extends AInitConfig {

	private static final Logger log = Logger.getLogger(InitConfigTest.class.getName());

	public static final int NUM_PEERS = 8;
	public static final int NUM_USERS = 2;

	// Hero and Villain user accounts
	public static final Address HERO = Address.create(9);
	public static final Address VILLAIN = Address.create(10);

	public static final Address FIRST_PEER = Address.create(11);

	public static AKeyPair HERO_KP;
	public static AKeyPair VILLAIN_KP;

	private InitConfigTest(AKeyPair userKeyPairs[], AKeyPair peerKeyPairs[], boolean isStandardLibrary) {
		this.userKeyPairs = userKeyPairs;
		this.peerKeyPairs = peerKeyPairs;
		this.isStandardLibrary = isStandardLibrary;
	}

	public static InitConfigTest create() {
		AKeyPair userKeyPairs[] = new Ed25519KeyPair[NUM_USERS];
		AKeyPair peerKeyPairs[] = new Ed25519KeyPair[NUM_PEERS];

		for (int i = 0; i < NUM_USERS; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(543212345 + i);
			userKeyPairs[i] = kp;
		}

		for (int i = 0; i < NUM_PEERS; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(123454321 + i);
			peerKeyPairs[i] = kp;
		}

		HERO_KP=userKeyPairs[0];
		VILLAIN_KP=userKeyPairs[1];
		return new InitConfigTest(userKeyPairs, peerKeyPairs, true);
	}
}
