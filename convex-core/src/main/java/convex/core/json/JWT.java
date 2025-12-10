package convex.core.json;

import java.util.Base64;

import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.util.BlobBuilder;
import convex.core.util.JSON;

public class JWT {

	static Base64.Decoder decoder=Base64.getUrlDecoder();
	
	// JWT encoding should be Base64URL without padding
	static Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();
	
	/**
	 * Get the claims string for a JWT before encoding
	 * @param claimData Structured claim data
	 * @return Claims String in UTF-8 JSON
	 */
	public static AString claims(AMap<AString,ACell> claimData) {
		return JSON.toAString(claimData);
	}
	
	public static AString build(ABlob sig, AString header, AString claims) {
		BlobBuilder bb=new BlobBuilder();
		bb.append(encoder.encode(sig.getBytes()));
		bb.append('.');
		bb.append(encoder.encode(header.getBytes()));
		bb.append('.');
		bb.append(encoder.encode(claims.getBytes()));
		return bb.toAString();
	}

	public static AString encode(ABlobLike<?> data) {
		return Strings.wrap(encoder.encode(data.getBytes()));
	}
}
