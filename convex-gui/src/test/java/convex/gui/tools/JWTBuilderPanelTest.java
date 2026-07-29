package convex.gui.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public class JWTBuilderPanelTest {

	private static final AKeyPair ISSUER=AKeyPair.createSeeded(610L);
	private static final AKeyPair AUDIENCE=AKeyPair.createSeeded(611L);

	@Test
	public void testAccessTokenClaimsAndSignature() {
		long issuedAt=1_800_000_000L;
		AMap<AString,ACell> extra=Maps.of("role","operator");
		AMap<AString,ACell> claims=JWTBuilderPanel.buildAccessClaims(
			DID.forKey(ISSUER.getAccountKey()).toString(),
			"did:convex:13",
			"https://api.example",
			issuedAt,
			issuedAt+5,
			issuedAt+3600,
			"token-123",
			"desktop-client",
			"read write",
			extra);

		AString token=JWTBuilderPanel.signToken(claims,ISSUER,false);
		JWT parsed=JWT.parse(token);

		assertNotNull(parsed);
		assertTrue(parsed.verifyEdDSA(ISSUER.getAccountKey()));
		assertEquals(Strings.create("did:convex:13"),parsed.getClaims().get(JWT.SUB));
		assertEquals(CVMLong.create(issuedAt),parsed.getClaims().get(JWT.IAT));
		assertEquals(Strings.create("desktop-client"),
			parsed.getClaims().get(JWTBuilderPanel.CLIENT_ID));
		assertEquals(Strings.create("read write"),parsed.getClaims().get(JWTBuilderPanel.SCOPE));
		assertEquals(Strings.create("operator"),parsed.getClaims().get(Strings.create("role")));
	}

	@Test
	public void testAdditionalClaimsCannotReplaceRegisteredFields() {
		AMap<AString,ACell> extra=Maps.of("sub","attacker");

		assertThrows(IllegalArgumentException.class,() ->
			JWTBuilderPanel.buildAccessClaims(
				"issuer","subject","audience",10L,null,20L,
				"id","client",null,extra));
	}

	@Test
	public void testClientIDIsOptional() {
		AMap<AString,ACell> claims=JWTBuilderPanel.buildAccessClaims(
			"issuer","subject","audience",10L,null,20L,
			"id","",null,Maps.empty());

		assertFalse(claims.containsKey(JWTBuilderPanel.CLIENT_ID));
	}

	@Test
	public void testReadableDuration() {
		assertEquals("1h",JWTBuilderPanel.formatDuration(3600));
		assertEquals("1d 2h 3m 4s",JWTBuilderPanel.formatDuration(93_784));
	}

	@Test
	public void testUCANClaimsHeaderAndValidation() {
		long now=1_800_000_000L;
		AVector<ACell> capabilities=Vectors.of(
			Capability.create(Strings.create("did:key:test/w/"),Capability.CRUD_READ));
		AMap<AString,ACell> claims=JWTBuilderPanel.buildUCANClaims(
			ISSUER.getAccountKey(),
			AUDIENCE.getAccountKey(),
			now-10,
			now+3600,
			"0x010203",
			capabilities,
			Vectors.empty(),
			Maps.of("purpose","test"));

		AString token=JWTBuilderPanel.signToken(claims,ISSUER,true);
		JWT parsed=JWT.parse(token);

		assertNotNull(parsed);
		assertEquals("EdDSA",parsed.getAlgorithm());
		assertEquals(DID.forKey(ISSUER.getAccountKey()),parsed.getClaims().get(UCAN.ISS));
		assertEquals(1,((AVector<?>)parsed.getClaims().get(UCAN.ATT)).count());
		assertNotNull(UCANValidator.validateJWT(token,now,convex.auth.did.DIDVerifier.CONVEX));
	}

	@Test
	public void testUCANDIDKeyIssuerMustMatchSigner() {
		AMap<AString,ACell> claims=JWTBuilderPanel.buildUCANClaims(
			ISSUER.getAccountKey(),
			AUDIENCE.getAccountKey(),
			null,
			1_900_000_000L,
			"nonce",
			Vectors.empty(),
			Vectors.empty(),
			null);

		IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
			() -> JWTBuilderPanel.signToken(claims,AUDIENCE,true));
		assertTrue(error.getMessage().contains("does not match"));
	}
}
