package convex.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
		assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",JWT.encode(head).toString());
		
		AString claims=Strings.create("{\"sub\":\"1234567890\",\"name\":\"John Doe\",\"admin\":true,\"iat\":1516239022}");
		assertEquals("eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0",JWT.encode(claims).toString());

	}
}
