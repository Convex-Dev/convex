package convex.x402.model;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.x402.Fields;

/**
 * x402 v2 ResourceInfo: describes the protected resource a payment buys.
 *
 * @param url URL of the protected resource
 * @param description Human-readable description, may be null
 * @param mimeType MIME type of the expected response, may be null
 */
public record ResourceInfo(String url, String description, String mimeType) {

	public AMap<AString, ACell> toJSON() {
		AMap<AString, ACell> m = Maps.of(Fields.URL, Strings.create(url == null ? "" : url));
		if (description != null) m = m.assoc(Fields.DESCRIPTION, Strings.create(description));
		if (mimeType != null) m = m.assoc(Fields.MIME_TYPE, Strings.create(mimeType));
		return m;
	}

	public static ResourceInfo fromJSON(AMap<AString, ACell> json) {
		if (json == null) return null;
		return new ResourceInfo(
				Model.optString(json, Fields.URL),
				Model.optString(json, Fields.DESCRIPTION),
				Model.optString(json, Fields.MIME_TYPE));
	}
}
