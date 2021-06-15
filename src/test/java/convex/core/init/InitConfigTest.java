package convex.core.init;

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
import convex.core.init.AInitConfig;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;


public class InitConfigTest extends InitConfig {

	public static final int NUM_PEERS = 8;
	public static final int NUM_USERS = 2;

	public static final AKeyPair USER_KEYPAIRS[] = new Ed25519KeyPair[NUM_USERS];
	public static final AKeyPair PEER_KEYPAIRS[] = new Ed25519KeyPair[NUM_PEERS];

	public static final Address HERO_ADDRESS = Init.calcAddress(0, 0, 0);
	public static final AKeyPair HERO_KEYPAIR;

	public static final Address VILLAIN_ADDRESS = Init.calcAddress(0, 0, 1);
	public static final AKeyPair VILLAIN_KEYPAIR;

	public static final Address FIRST_PEER_ADDRESS = Init.calcAddress(NUM_USERS, 0, 0);
	public static final AKeyPair FIRST_PEER_KEYPAIR;
	public static final AccountKey FIRST_PEER_KEY;

	static {
		for (int i = 0; i < NUM_USERS; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(543212345 + i);
			USER_KEYPAIRS[i] = kp;
		}

		for (int i = 0; i < NUM_PEERS; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(123454321 + i);
			PEER_KEYPAIRS[i] = kp;
		}
		HERO_KEYPAIR = USER_KEYPAIRS[0];
		VILLAIN_KEYPAIR = USER_KEYPAIRS[1];
		FIRST_PEER_KEYPAIR = PEER_KEYPAIRS[0];
		FIRST_PEER_KEY = FIRST_PEER_KEYPAIR.getAccountKey();

	}

	protected InitConfigTest(AKeyPair userKeyPairs[], AKeyPair peerKeyPairs[]) {
		super(userKeyPairs, peerKeyPairs);
	}

	public static InitConfigTest create() {
		return new InitConfigTest(USER_KEYPAIRS, PEER_KEYPAIRS);
	}
}
