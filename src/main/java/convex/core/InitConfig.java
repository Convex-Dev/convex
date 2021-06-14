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

public class InitConfig extends AInitConfig {

	private static final Logger log = Logger.getLogger(InitConfig.class.getName());

	private InitConfig(AKeyPair userKeyPairs[], AKeyPair peerKeyPairs[], boolean isStandardLibrary) {
		this.userKeyPairs = userKeyPairs;
		this.peerKeyPairs = peerKeyPairs;
		this.isStandardLibrary = isStandardLibrary;
	}


	public static AInitConfig create(AKeyPair userKeyPairs[], AKeyPair peerKeyPairs[],  boolean isStandardLibrary) {
		return new InitConfig(userKeyPairs, peerKeyPairs, isStandardLibrary);
	}
}
