package convex.x402.model;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Shared JSON extraction helpers for x402 model types.
 */
class Model {
	static String optString(AMap<AString, ACell> map, AString key) {
		if (map == null) return null;
		AString v = RT.ensureString(map.get(key));
		return (v == null) ? null : v.toString();
	}

	static String reqString(AMap<AString, ACell> map, AString key) {
		String v = optString(map, key);
		if (v == null) throw new IllegalArgumentException("Missing required x402 field: " + key);
		return v;
	}

	static long optLong(AMap<AString, ACell> map, AString key, long dflt) {
		if (map == null) return dflt;
		ACell raw = map.get(key);
		if (raw == null) return dflt;
		CVMLong v = RT.ensureLong(raw);
		if (v == null) throw new IllegalArgumentException("x402 field must be an integer: " + key);
		return v.longValue();
	}
}
