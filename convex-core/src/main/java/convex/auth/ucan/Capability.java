package convex.auth.ucan;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;

/**
 * Capability construction and matching for UCAN tokens.
 *
 * <p>Provides constants, factory methods, and attenuation matching.
 * The primary interface uses AString (CVM native strings) throughout.</p>
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

	// ========== Attenuation matching — primary AString interface ==========

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
	 * <p>This is the primary implementation using CVM-native AString values.</p>
	 *
	 * @param grantWith Granted resource path
	 * @param grantCan Granted ability
	 * @param requestWith Requested resource path
	 * @param requestCan Requested ability
	 * @return true if the grant covers the request
	 */
	public static boolean covers(AString grantWith, AString grantCan,
			AString requestWith, AString requestCan) {
		if (grantCan == null || requestCan == null) return false;

		// Resource attenuation
		if (grantWith != null && requestWith != null) {
			if (!resourceCovers(grantWith, requestWith)) return false;
		}
		// null grantWith = any resource (wildcard)

		// Ability attenuation
		return abilityCovers(grantCan, requestCan);
	}

	/**
	 * Checks whether a granted resource path covers a requested resource path.
	 *
	 * <p>Uses path prefix matching with boundary awareness:</p>
	 * <ul>
	 *   <li>Exact match: {@code "w/decisions"} covers {@code "w/decisions"}</li>
	 *   <li>Prefix: {@code "w/decisions"} covers {@code "w/decisions/INV-123"}</li>
	 *   <li>Trailing slash: {@code "w/decisions/"} covers {@code "w/decisions"} and children</li>
	 *   <li>Empty grant covers any resource</li>
	 * </ul>
	 */
	public static boolean resourceCovers(AString grant, AString request) {
		if (grant == null) return true;
		if (request == null) return true;

		long gLen = grant.count();
		long rLen = request.count();

		// Empty grant covers everything
		if (gLen == 0) return true;

		// Exact match (interned strings may be identical objects)
		if (grant.equals(request)) return true;

		// Check if request starts with grant
		if (rLen > gLen && request.startsWith(grant)) return true;

		// Trailing slash: "w/records/" covers "w/records"
		if (gLen > 0 && grant.charAt(gLen - 1) == '/' && rLen == gLen - 1) {
			if (request.equals(grant.slice(0, gLen - 1))) return true;
		}

		return false;
	}

	/**
	 * Checks whether a granted ability covers a requested ability.
	 *
	 * <p>Uses UCAN prefix hierarchy:</p>
	 * <ul>
	 *   <li>{@code *} covers any ability</li>
	 *   <li>Exact match</li>
	 *   <li>Prefix with {@code /} boundary: {@code "crud"} covers {@code "crud/read"}</li>
	 * </ul>
	 */
	public static boolean abilityCovers(AString grant, AString request) {
		if (grant == null) return false;

		// Top ability covers everything
		if (TOP.equals(grant)) return true;

		// Exact match
		if (grant.equals(request)) return true;

		// Prefix: grant must be followed by "/" in request
		long gLen = grant.count();
		long rLen = request.count();
		if (rLen > gLen + 1 && request.charAt(gLen) == '/') {
			if (request.startsWith(grant)) return true;
		}

		return false;
	}

	// ========== Convenience overloads ==========

	/**
	 * Checks whether a granted capability map covers a requested resource and ability.
	 *
	 * @param grant Capability map with "with" and "can" fields
	 * @param requestWith Requested resource path
	 * @param requestCan Requested ability
	 * @return true if the grant covers the request
	 */
	public static boolean covers(AMap<AString, ACell> grant, AString requestWith, AString requestCan) {
		AString withStr = RT.ensureString(grant.get(WITH));
		AString canStr = RT.ensureString(grant.get(CAN));
		return covers(withStr, canStr, requestWith, requestCan);
	}

	/**
	 * String convenience overload — delegates to AString primary implementation.
	 */
	public static boolean covers(String grantWith, String grantCan,
			String requestWith, String requestCan) {
		return covers(
			grantWith != null ? Strings.create(grantWith) : null,
			grantCan != null ? Strings.create(grantCan) : null,
			requestWith != null ? Strings.create(requestWith) : null,
			requestCan != null ? Strings.create(requestCan) : null);
	}

	/**
	 * String convenience overload for map grant — delegates to AString primary.
	 */
	public static boolean covers(AMap<AString, ACell> grant, String requestWith, String requestCan) {
		return covers(grant,
			requestWith != null ? Strings.create(requestWith) : null,
			requestCan != null ? Strings.create(requestCan) : null);
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
