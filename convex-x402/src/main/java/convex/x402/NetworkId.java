package convex.x402;

import java.util.List;
import java.util.Set;

import convex.core.data.Hash;
import convex.core.util.CAIP;

/**
 * CAIP-2 identity of a Convex network for x402 purposes.
 *
 * <p>A network reference takes one of two equivalent forms (see CAD042):</p>
 * <ul>
 * <li>The lowercase hex of the network's genesis hash truncated to 32
 * characters, e.g. {@code convex:9f8e...}. Registry-free, so it works for any
 * private or local network.</li>
 * <li>A well-known alias from {@link CAIP}: {@code convex:protonet},
 * {@code convex:testnet}, {@code convex:main}, {@code convex:local}. For now
 * all aliases denote the same network wherever they are accepted; the CAD042
 * registry will diverge when mainnet launches.</li>
 * </ul>
 */
public final class NetworkId {
	/** CAIP-2 namespace for Convex networks */
	public static final String NAMESPACE = "convex";

	/** Length in hex characters of a genesis hash CAIP-2 reference */
	public static final int REFERENCE_HEX_LENGTH = 32;

	private static final Set<String> ALIASES = Set.of(
			CAIP.PROTONET.toString(),
			CAIP.TESTNET.toString(),
			CAIP.MAINNET.toString(),
			CAIP.LOCALNET.toString());

	private final Hash genesisHash;
	private final String canonical;

	private NetworkId(Hash genesisHash) {
		this.genesisHash = genesisHash;
		this.canonical = NAMESPACE + ":"
				+ genesisHash.toHexString().substring(0, REFERENCE_HEX_LENGTH).toLowerCase();
	}

	/**
	 * Creates a network identity from a genesis hash.
	 * @param genesisHash The network's genesis hash (network ID)
	 * @return Network identity
	 */
	public static NetworkId create(Hash genesisHash) {
		if (genesisHash == null) throw new IllegalArgumentException("Genesis hash required");
		return new NetworkId(genesisHash);
	}

	/**
	 * Gets the canonical CAIP-2 identifier, {@code convex:} plus the truncated
	 * genesis hash.
	 * @return Canonical CAIP-2 network identifier
	 */
	public String canonical() {
		return canonical;
	}

	public Hash getGenesisHash() {
		return genesisHash;
	}

	/**
	 * Tests whether a CAIP-2 network identifier denotes this network, in either
	 * the genesis hash or well-known alias form.
	 * @param caip2 CAIP-2 network identifier, e.g. {@code convex:protonet}
	 * @return true if the identifier denotes this network
	 */
	public boolean matches(String caip2) {
		if (caip2 == null) return false;
		if (canonical.equals(caip2)) return true;
		return ALIASES.contains(caip2);
	}

	/**
	 * Gets all identifier forms recognised for this network, canonical form first.
	 * @return Recognised CAIP-2 identifiers
	 */
	public List<String> knownForms() {
		return List.of(canonical,
				CAIP.PROTONET.toString(),
				CAIP.TESTNET.toString(),
				CAIP.MAINNET.toString(),
				CAIP.LOCALNET.toString());
	}

	@Override
	public String toString() {
		return canonical;
	}
}
