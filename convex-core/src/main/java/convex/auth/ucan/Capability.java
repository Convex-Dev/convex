package convex.auth.ucan;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;

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

	// Standard abilities
	/** Top ability — proves any other ability */
	public static final AString TOP = Strings.intern("*");
	public static final AString CRUD = Strings.intern("crud");
	public static final AString CRUD_READ = Strings.intern("crud/read");
	public static final AString CRUD_WRITE = Strings.intern("crud/write");
	public static final AString CRUD_DELETE = Strings.intern("crud/delete");

	// Convex-specific abilities
	public static final AString CONVEX_TRANSFER = Strings.intern("convex/transfer");
	public static final AString CONVEX_CALL = Strings.intern("convex/call");
	public static final AString CONVEX_DEPLOY = Strings.intern("convex/deploy");
	public static final AString CONVEX_WILDCARD = Strings.intern("convex/*");

	/**
	 * Create a capability with resource and ability only (no caveats).
	 *
	 * @param resource Resource URI (e.g. "did:key:zAlice.../w/")
	 * @param ability Ability string (e.g. "crud/read")
	 * @return Capability map
	 */
	public static AMap<AString, ACell> create(AString resource, AString ability) {
		return Maps.of(WITH, resource, CAN, ability);
	}

	/**
	 * Create a capability with resource, ability, and caveats.
	 *
	 * @param resource Resource URI
	 * @param ability Ability string
	 * @param caveats Caveat map (e.g. {"maxSize" 1000000})
	 * @return Capability map
	 */
	public static AMap<AString, ACell> create(AString resource, AString ability,
			AMap<AString, ACell> caveats) {
		return Maps.of(WITH, resource, CAN, ability, NB, caveats);
	}

	/**
	 * Checks whether a granted capability covers a requested capability.
	 *
	 * <p>A grant covers a request when:</p>
	 * <ul>
	 *   <li>The grant's resource ({@code with}) is a prefix of the requested resource
	 *       (path attenuation — {@code /w/} covers {@code /w/notes})</li>
	 *   <li>The grant's ability ({@code can}) is a prefix of the requested ability
	 *       (ability attenuation — {@code crud} covers {@code crud/read}),
	 *       or the grant's ability is {@code *} (top — covers everything)</li>
	 * </ul>
	 *
	 * @param grantWith Granted resource path (e.g. "/w/")
	 * @param grantCan Granted ability (e.g. "crud/read" or "*")
	 * @param requestWith Requested resource path (e.g. "/w/notes")
	 * @param requestCan Requested ability (e.g. "crud/read")
	 * @return true if the grant covers the request
	 */
	public static boolean covers(String grantWith, String grantCan,
			String requestWith, String requestCan) {
		// Resource attenuation: grant path must be a prefix of request path.
		// Normalise: /w/ covers /w and /w/anything.
		// Exact match always covers. Prefix match requires the grant to end
		// with / or the request to have / at the boundary.
		if (!requestWith.equals(grantWith)
			&& !requestWith.startsWith(grantWith)
			&& !(grantWith.endsWith("/") && (grantWith.substring(0, grantWith.length() - 1)).equals(requestWith))) {
			return false;
		}

		// Ability attenuation: * covers all, exact match, or prefix match
		if ("*".equals(grantCan)) return true;
		if (requestCan.equals(grantCan)) return true;
		if (requestCan.startsWith(grantCan + "/")) return true;
		return false;
	}

	/**
	 * Checks whether a granted capability map covers a requested resource and ability.
	 *
	 * @param grant Capability map with "with" and "can" fields
	 * @param requestWith Requested resource path
	 * @param requestCan Requested ability
	 * @return true if the grant covers the request
	 */
	public static boolean covers(AMap<AString, ACell> grant, String requestWith, String requestCan) {
		AString withStr = RT.ensureString(grant.get(WITH));
		AString canStr = RT.ensureString(grant.get(CAN));
		if (withStr == null || canStr == null) return false;
		return covers(withStr.toString(), canStr.toString(), requestWith, requestCan);
	}

	/**
	 * Build a resource URI string for Convex accounts.
	 *
	 * @param type Resource type ("account" or "actor")
	 * @param address Convex address number
	 * @return Resource URI (e.g. "convex:account:#42")
	 */
	public static AString resourceURI(String type, long address) {
		return Strings.create("convex:" + type + ":#" + address);
	}
}
