package convex.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Symbols;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

public class JWTTest {

	@Test public void testClaims() {
		assertEquals("{}",JWT.claims(Maps.empty()).toString());
		assertEquals("{\"1\":\"foo\"}",JWT.claims(Maps.of(1,Symbols.FOO)).toString());
	}
	
	// see: https://www.jwt.io/
	@Test public void testExample() {
		AString secret=Strings.create("a-string-secret-at-least-256-bits-long");
		
		AString head=Strings.create("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
		AString base64Head=JWT.encode(head);
		assertEquals(JWT.HEADER_HS256,base64Head);
		assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",base64Head.toString());
		
		AString claims=Strings.create("{\"sub\":\"1234567890\",\"name\":\"John Doe\",\"admin\":true,\"iat\":1516239022}");
		AString base64Claims=JWT.encode(claims);
		assertEquals("eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0",base64Claims.toString());

		AString toSign=base64Head.append(".").append(base64Claims);
		AString sig=JWT.signHS256(toSign, secret);
		assertEquals("KMUFsIDTnFmyG3nMiGM6H9FNFUROf3wh7SmqJp-QV30",sig.toString());
	}
	
	// See: https://datatracker.ietf.org/doc/html/rfc7515#appendix-A.1.1
	@Test public void testJWSExample() {
		AString head=Strings.create("{\"typ\":\"JWT\",\r\n \"alg\":\"HS256\"}");
		AString base64Head=JWT.encode(head);
		assertEquals("eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9",base64Head.toString());

		AString claims=Strings.create("{\"iss\":\"joe\",\r\n \"exp\":1300819380,\r\n \"http://example.com/is_root\":true}");
		AString base64Claims=JWT.encode(claims);
		assertEquals("eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ",base64Claims.toString());

		AString jwkKey=Strings.create("AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow");
		byte[] key=JWT.decodeRaw(jwkKey);
		//System.err.println(Strings.wrap(key));
		//System.err.println(Arrays.toString(key));
		
		AString message=base64Head.append(".").append(base64Claims);
		
		AString sig=JWT.signHS256(message, key);
		assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",sig.toString());
		
		AString jwt=message.append(".").append(sig);
		assertTrue(JWT.verifyHS256(jwt, key));
	}
}
