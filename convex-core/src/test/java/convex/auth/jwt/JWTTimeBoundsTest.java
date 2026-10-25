package convex.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Strings;

public class JWTTimeBoundsTest {

	/** Use raw JSON so serialisation cannot alter the numeric or malformed input. */
	private static JWT token(String claims) {
		JWT jwt = JWT.parse(JWT.build(Strings.create("{\"alg\":\"HS256\"}"),
			Strings.create(claims), Blob.EMPTY));
		assertNotNull(jwt);
		return jwt;
	}

	@Test public void testOptionalBoundsAndScope() {
		JWT jwt = token("{}");
		assertNull(jwt.validateTimeBounds(Instant.MIN));
		assertNull(jwt.validateTimeBounds(Instant.MAX));
		assertFalse(jwt.verifyHS256(new byte[32]), "Temporal success does not verify a signature");
		assertNull(token("{\"iat\":\"ignored\",\"iss\":null,\"aud\":false}")
			.validateTimeBounds(Instant.EPOCH));
		assertThrows(NullPointerException.class, () -> jwt.validateTimeBounds(null));
	}

	@Test public void testInclusiveNotBeforeExclusiveExpiry() {
		JWT jwt = token("{\"nbf\":100,\"exp\":101}");
		Instant start = Instant.ofEpochSecond(100);
		Instant end = Instant.ofEpochSecond(101);
		assertSame(JWT.NOT_YET_VALID, jwt.validateTimeBounds(start.minusNanos(1)));
		assertNull(jwt.validateTimeBounds(start));
		assertNull(jwt.validateTimeBounds(end.minusNanos(1)));
		assertSame(JWT.EXPIRED, jwt.validateTimeBounds(end));
		assertSame(JWT.EXPIRED, jwt.validateTimeBounds(end.plusNanos(1)));
		assertSame(JWT.EXPIRED, token("{\"nbf\":100,\"exp\":100}").validateTimeBounds(start));
	}

	@Test public void testFractionalAndNegativeDates() {
		for (String value : new String[] {"100.5", "1.005e2", "-0.5"}) {
			Instant boundary = value.equals("-0.5")
				? Instant.ofEpochSecond(-1, 500_000_000) : Instant.ofEpochSecond(100, 500_000_000);
			JWT exp = token("{\"exp\":" + value + "}");
			JWT nbf = token("{\"nbf\":" + value + "}");
			assertNull(exp.validateTimeBounds(boundary.minusNanos(1)));
			assertSame(JWT.EXPIRED, exp.validateTimeBounds(boundary));
			assertSame(JWT.NOT_YET_VALID, nbf.validateTimeBounds(boundary.minusNanos(1)));
			assertNull(nbf.validateTimeBounds(boundary));
		}
		assertSame(JWT.EXPIRED, token("{\"exp\":-0.0}").validateTimeBounds(Instant.EPOCH));
		assertNull(token("{\"nbf\":-0.0}").validateTimeBounds(Instant.EPOCH));
	}

	@Test public void testBinary64PrecisionContract() {
		// Binary64 0.1 is slightly greater than the exact decimal time 0.1 seconds.
		Instant decimalTenth = Instant.ofEpochSecond(0, 100_000_000);
		JWT exp = token("{\"exp\":0.1}");
		JWT nbf = token("{\"nbf\":0.1}");
		assertNull(exp.validateTimeBounds(decimalTenth));
		assertSame(JWT.NOT_YET_VALID, nbf.validateTimeBounds(decimalTenth));
		assertSame(JWT.EXPIRED, exp.validateTimeBounds(decimalTenth.plusNanos(1)));
		assertNull(nbf.validateTimeBounds(decimalTenth.plusNanos(1)));
	}

	@Test public void testMalformedClaimsAndPrecedence() {
		for (String value : new String[] {"null", "\"100\"", "true", "false", "[]", "{}",
			"1e400", "-1e400"}) {
			assertSame(JWT.INVALID_EXP, token("{\"exp\":" + value + "}")
				.validateTimeBounds(Instant.EPOCH), value);
			assertSame(JWT.INVALID_NBF, token("{\"nbf\":" + value + "}")
				.validateTimeBounds(Instant.EPOCH), value);
		}
		assertSame(JWT.INVALID_EXP, token("{\"exp\":null,\"nbf\":null}").validateTimeBounds(Instant.EPOCH));
		assertSame(JWT.INVALID_NBF, token("{\"exp\":0,\"nbf\":null}").validateTimeBounds(Instant.EPOCH));
		assertSame(JWT.EXPIRED, token("{\"exp\":0,\"nbf\":1}").validateTimeBounds(Instant.EPOCH));
		assertSame(Strings.intern("invalid-exp"), JWT.INVALID_EXP);
		assertSame(Strings.intern("invalid-nbf"), JWT.INVALID_NBF);
		assertSame(Strings.intern("expired"), JWT.EXPIRED);
		assertSame(Strings.intern("not-yet-valid"), JWT.NOT_YET_VALID);
	}

	@Test public void testLargeIntegerPrecision() {
		long seconds = 9_007_199_254_740_993L; // 2^53 + 1: cannot round-trip through double
		Instant boundary = Instant.ofEpochSecond(seconds);
		JWT exp = token("{\"exp\":" + seconds + "}");
		JWT nbf = token("{\"nbf\":" + seconds + "}");
		assertNull(exp.validateTimeBounds(boundary.minusNanos(1)));
		assertSame(JWT.EXPIRED, exp.validateTimeBounds(boundary));
		assertSame(JWT.NOT_YET_VALID, nbf.validateTimeBounds(boundary.minusNanos(1)));
		assertNull(nbf.validateTimeBounds(boundary));
	}

	@Test public void testExtremeBoundsWithoutTimestampConversion() {
		for (String value : new String[] {"9223372036854775807", "9223372036854775808",
			"1" + "0".repeat(400), "1.7976931348623157e308"}) {
			assertNull(token("{\"exp\":" + value + "}").validateTimeBounds(Instant.MAX));
			assertSame(JWT.NOT_YET_VALID, token("{\"nbf\":" + value + "}").validateTimeBounds(Instant.MAX));
			assertSame(JWT.EXPIRED, token("{\"exp\":-" + value + "}").validateTimeBounds(Instant.MIN));
			assertNull(token("{\"nbf\":-" + value + "}").validateTimeBounds(Instant.MIN));
		}
		assertNull(token("{\"nbf\":" + Instant.MIN.getEpochSecond() + "}").validateTimeBounds(Instant.MIN));
		assertSame(JWT.EXPIRED, token("{\"exp\":" + Instant.MAX.getEpochSecond() + "}")
			.validateTimeBounds(Instant.MAX));
	}
}
