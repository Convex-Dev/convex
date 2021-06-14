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

public abstract class AInitConfig {


	// standard accounts numbers
	public static final Address NULL_ADDRESS = Address.create(0);
	public static final Address INIT_ADDRESS = Address.create(1);

	public static final Address RESERVED_ADDRESS = Address.create(2);
	public static final Address MAINBANK_ADDRESS = Address.create(3);
	public static final Address MAINPOOL_ADDRESS = Address.create(4);
	public static final Address LIVEPOOL_ADDRESS = Address.create(5);
	public static final Address ROOTFUND_ADDRESS = Address.create(6);

	// Built-in special accounts
	public static final Address MEMORY_EXCHANGE_ADDRESS = Address.create(7);
	public static final Address CORE_ADDRESS = Address.create(8);

	public static final Address BASE_FIRST_ADDRESS = Address.create(9);

	protected AKeyPair userKeyPairs[]  = new AKeyPair[0];
	protected AKeyPair peerKeyPairs[]  = new AKeyPair[0];
	protected boolean isStandardLibrary = true;


	public boolean isStandardLibrary() {
		return this.isStandardLibrary;
	}
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

	public Address getUserAddress(int index) {
		return Address.create(BASE_FIRST_ADDRESS.longValue() + index);
	}

	public Address getPeerAddress(int index) {
		return Address.create(BASE_FIRST_ADDRESS.longValue() + getUserCount() + index);
	}

	public Address getLibraryAddress(int index) {
		return Address.create(BASE_FIRST_ADDRESS.longValue() + getUserCount() + getPeerCount() + index);
	}
}
