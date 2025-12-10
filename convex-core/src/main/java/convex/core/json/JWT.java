package convex.core.json;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
	
	static AString HEADER_HS256 = encode(Strings.intern("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
	
	/**
	 * Get the claims string for a JWT before encoding
	 * @param claimData Structured claim data
	 * @return Claims String in UTF-8 JSON
	 */
	public static AString claims(AMap<AString,ACell> claimData) {
		return JSON.toAString(claimData);
	}
	
	public static AString build(AString header, AString claims, ABlob sig) {
		BlobBuilder bb=new BlobBuilder();
		bb.append(encoder.encode(header.getBytes()));
		bb.append('.');
		bb.append(encoder.encode(claims.getBytes()));
		bb.append('.');
		bb.append(encoder.encode(sig.getBytes()));
		return bb.toAString();
	}
	


	public static AString encode(ABlobLike<?> data) {
		return encode(data.getBytes());
	}
	
	public static AString encode(byte[] data) {
		return Strings.wrap(encoder.encode(data));
	}
	
	public static byte[] decodeRaw(AString encodedBase64) {
		return decoder.decode(encodedBase64.getBytes());
	}
	

	public static AString signHS256(ABlobLike<?> message, ABlobLike<?> secret) {
		return signHS256(message,secret.getBytes());
	}
	
	public static AString signHS256(ABlobLike<?> message, byte[] secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec keySpec = new SecretKeySpec(secret, "HmacSHA256");
			mac.init(keySpec);
			byte[] rawSignature = mac.doFinal(message.getBytes());
			return encode(rawSignature);
		} catch (NoSuchAlgorithmException  e) {
			throw new Error("HMAC algorithm failure", e);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException("Invalid key",e);
		}
	}
	
	public static boolean verifyHS256(AString jwt,byte[] secret) {
		int lastdot=jwt.toString().lastIndexOf('.');
		if (lastdot<0) throw new IllegalArgumentException("Invalid JWT format, missing last dot");
		AString msg=jwt.slice(0,lastdot);
		AString sig=signHS256(msg,secret);
		return sig.equals(jwt.slice(lastdot+1));
	}


}
