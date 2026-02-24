package convex.auth.ucan;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Minimal capability construction helpers for UCAN tokens.
 *
 * <p>Provides constants and factory methods for building capability maps.
 * Attenuation matching logic is deferred to a later design phase.</p>
 *
 * <p>Capability structure:</p>
 * <pre>
 * {"with" "convex:account:#42"
 *  "can"  "convex/transfer"
 *  "nb"   {"max_amount" 1000000000 "to" #{55 78}}}
 * </pre>
 */
public class Capability {

	// Capability map keys
	public static final AString WITH = Strings.intern("with");
	public static final AString CAN = Strings.intern("can");
	public static final AString NB = Strings.intern("nb");

	// Action strings
	public static final AString CONVEX_TRANSFER = Strings.intern("convex/transfer");
	public static final AString CONVEX_CALL = Strings.intern("convex/call");
	public static final AString CONVEX_DEPLOY = Strings.intern("convex/deploy");
	public static final AString CONVEX_WILDCARD = Strings.intern("convex/*");

	/**
	 * Create a capability with resource and action only (no caveats).
	 *
	 * @param resource Resource URI (e.g. "convex:account:#42")
	 * @param action Action string (e.g. "convex/transfer")
	 * @return Capability map
	 */
	public static AMap<AString, ACell> create(AString resource, AString action) {
		return Maps.of(WITH, resource, CAN, action);
	}

	/**
	 * Create a capability with resource, action, and caveats.
	 *
	 * @param resource Resource URI
	 * @param action Action string
	 * @param caveats Caveat map (e.g. {"max_amount" 100000000})
	 * @return Capability map
	 */
	public static AMap<AString, ACell> create(AString resource, AString action,
			AMap<AString, ACell> caveats) {
		return Maps.of(WITH, resource, CAN, action, NB, caveats);
	}

	/**
	 * Build a resource URI string.
	 *
	 * @param type Resource type ("account" or "actor")
	 * @param address Convex address number
	 * @return Resource URI (e.g. "convex:account:#42")
	 */
	public static AString resourceURI(String type, long address) {
		return Strings.create("convex:" + type + ":#" + address);
	}
}
