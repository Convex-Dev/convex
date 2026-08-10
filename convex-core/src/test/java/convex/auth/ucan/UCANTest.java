package convex.auth.ucan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public class UCANTest {

	static final AKeyPair ROOT_KP = AKeyPair.createSeeded(1001);
	static final AKeyPair AGENT_A_KP = AKeyPair.createSeeded(1002);
	static final AKeyPair AGENT_B_KP = AKeyPair.createSeeded(1003);
	static final AKeyPair ROGUE_KP = AKeyPair.createSeeded(9999);

	static final long FUTURE_EXPIRY = System.currentTimeMillis() / 1000 + 3600; // 1 hour from now
	static final long PAST_EXPIRY = System.currentTimeMillis() / 1000 - 3600;   // 1 hour ago
	static final long NOW = System.currentTimeMillis() / 1000;

	// ===== Token Creation Tests =====

	@Test
	public void testCreateRootToken() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), Vectors.empty());

		assertNotNull(token);
		assertNotNull(token.getIssuer());
		assertNotNull(token.getAudience());
		assertNotNull(token.getNonce());
		assertNotNull(token.getSignature());
		assertNotNull(token.getPayload());
		assertEquals(Long.valueOf(FUTURE_EXPIRY), token.getExpiry());
		assertNull(token.getNotBefore());
		assertEquals(0, token.getCapabilities().count());
		assertEquals(0, token.getProofs().count());
	}

	@Test
	public void testSignatureValid() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		assertTrue(token.verifySignature());
	}

	@Test
	public void testNonceUniqueness() {
		UCAN t1 = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);
		UCAN t2 = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		assertNotEquals(t1.getNonce().toString(), t2.getNonce().toString());
	}

	@Test
	public void testDIDDerivation() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		String iss = token.getIssuer().toString();
		String aud = token.getAudience().toString();

		assertTrue(iss.startsWith("did:key:z6Mk"));
		assertTrue(aud.startsWith("did:key:z6Mk"));
		assertNotEquals(iss, aud);

		// Round-trip: key -> DID -> key
		assertEquals(ROOT_KP.getAccountKey(), token.getIssuerKey());
		assertEquals(AGENT_A_KP.getAccountKey(), token.getAudienceKey());
	}

	@Test
	public void testBuildPayloadWithDIDWebAudience() {
		AString audience = Strings.create("did:web:covia.ai");
		AString nonce = Strings.create("test-nonce");
		AMap<AString, ACell> payload = UCAN.buildPayload(
			ROOT_KP.getAccountKey(), audience, FUTURE_EXPIRY,
			null, nonce, null, null, null);

		assertEquals(audience, payload.get(UCAN.AUD));
		assertEquals(nonce, payload.get(UCAN.NNC));
		assertNull(UCAN.fromPayload(payload,
			ROOT_KP.sign(convex.core.data.Ref.get(payload).getEncoding())).getAudienceKey());
	}

	@Test
	public void testBuildPayloadRejectsInvalidAudienceDID() {
		assertThrows(IllegalArgumentException.class, () -> UCAN.buildPayload(
			ROOT_KP.getAccountKey(), Strings.create("did:web:"),
			FUTURE_EXPIRY, null, null, null, null));
	}

	@Test
	public void testSignJWTPayloadRequiresMatchingIssuerKey() {
		AMap<AString, ACell> payload = UCAN.buildPayload(
			ROOT_KP.getAccountKey(), Strings.create("did:web:covia.ai"),
			FUTURE_EXPIRY, null, null, null, null);

		AString token = UCAN.signJWT(payload, ROOT_KP);
		assertNotNull(UCANValidator.validateJWT(token, NOW, convex.auth.did.DIDVerifier.CONVEX));
		assertThrows(IllegalArgumentException.class, () -> UCAN.signJWT(payload, ROGUE_KP));
	}

	@Test
	public void testParseRoundTrip() {
		UCAN original = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		AMap<AString, ACell> map = original.toMap();
		assertNotNull(map.get(UCAN.HEADER));
		assertNotNull(map.get(UCAN.PAYLOAD));
		assertNotNull(map.get(UCAN.SIG));

		UCAN parsed = UCAN.parse(map);
		assertNotNull(parsed);
		assertEquals(original.getIssuer(), parsed.getIssuer());
		assertEquals(original.getAudience(), parsed.getAudience());
		assertEquals(original.getExpiry(), parsed.getExpiry());
		assertEquals(original.getNonce(), parsed.getNonce());
		assertTrue(parsed.verifySignature());
	}

	@Test
	public void testCreateWithCapabilities() {
		AMap<AString, ACell> cap = Capability.create(
			Capability.resourceURI("account", 42),
			Capability.CONVEX_TRANSFER
		);
		AVector<ACell> caps = Vectors.of(cap);

		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, caps, null);

		assertEquals(1, token.getCapabilities().count());
		assertTrue(token.verifySignature());
	}

	@Test
	public void testCreateWithNotBefore() {
		long nbf = NOW - 60; // 1 minute ago
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, nbf, null, null);

		assertNotNull(token.getNotBefore());
		assertEquals(nbf, token.getNotBefore().longValue());
		assertTrue(token.verifySignature());
	}

	@Test
	public void testCreateNonExpiringToken() {
		UCAN token=UCAN.create(ROOT_KP,AGENT_A_KP.getAccountKey(),(Long)null,null,null);

		assertTrue(token.getPayload().containsKey(UCAN.EXP));
		assertNull(token.getPayload().get(UCAN.EXP));
		assertNull(token.getExpiry());
		assertTrue(token.verifySignature());
		assertNotNull(UCANValidator.validate(token,NOW));

		UCAN parsed=UCAN.parse(token.toMap());
		assertNotNull(parsed);
		assertTrue(parsed.getPayload().containsKey(UCAN.EXP));
		assertNull(parsed.getExpiry());
		assertNotNull(UCANValidator.validate(parsed,NOW));
	}

	@Test
	public void testNonExpiringTokenStillHonoursNotBefore() {
		UCAN token=UCAN.create(ROOT_KP,AGENT_A_KP.getAccountKey(),(Long)null,
			Long.valueOf(NOW+600),null,null);

		assertNull(UCANValidator.validate(token,NOW));
		assertNotNull(UCANValidator.validate(token,NOW+600));
	}

	@Test
	public void testParseMalformed() {
		assertNull(UCAN.parse(null));
		assertNull(UCAN.parse(Maps.empty()));
		assertNull(UCAN.parse(Maps.of(UCAN.PAYLOAD, Strings.create("not a map"))));
	}

	// ===== Validation Tests =====

	@Test
	public void testValidateRootToken() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		assertNotNull(UCANValidator.validate(token, NOW));
	}

	@Test
	public void testExpiredTokenFails() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			PAST_EXPIRY, null, null);

		assertNull(UCANValidator.validate(token, NOW));
	}

	@Test
	public void testMissingExpiryFails() {
		UCAN valid=UCAN.create(ROOT_KP,AGENT_A_KP.getAccountKey(),FUTURE_EXPIRY,null,null);
		AMap<AString,ACell> payload=valid.getPayload().dissoc(UCAN.EXP);
		UCAN missing=UCAN.fromPayload(payload,ROOT_KP.sign(
			convex.core.data.Ref.get(payload).getEncoding()));

		assertFalse(payload.containsKey(UCAN.EXP));
		assertNull(UCANValidator.validate(missing,NOW));
	}

	@Test
	public void testNotBeforeFails() {
		long futureNbf = NOW + 600; // 10 minutes in the future, before expiry
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, futureNbf, null, null);

		assertNull(UCANValidator.validate(token, NOW));
	}

	@Test
	public void testBadSignatureFails() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		// Tamper with the payload by creating a new UCAN with different payload but same sig
		AMap<AString, ACell> tamperedPayload = token.getPayload().assoc(
			UCAN.EXP, CVMLong.create(FUTURE_EXPIRY + 9999));
		AMap<AString, ACell> tamperedMap = Maps.of(
			UCAN.HEADER, Maps.of(UCAN.ALG, Strings.create("EdDSA"), UCAN.UCV, Strings.create("0.10.0")),
			UCAN.PAYLOAD, tamperedPayload,
			UCAN.SIG, token.getSignature()
		);
		UCAN tampered = UCAN.parse(tamperedMap);
		assertNotNull(tampered);
		assertFalse(tampered.verifySignature());
		assertNull(UCANValidator.validate(tampered, NOW));
	}

	@Test
	public void testWrongIssuerFails() {
		// Create token claiming to be from ROOT but signed by ROGUE
		UCAN token = UCAN.create(ROGUE_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		// Token validates because issuer DID matches ROGUE's key (it's self-consistent)
		assertNotNull(UCANValidator.validate(token, NOW));

		// But if we forge a payload with ROOT's DID and ROGUE's signature, it fails
		AMap<AString, ACell> forgedPayload = token.getPayload().assoc(
			UCAN.ISS, UCAN.toDIDKey(ROOT_KP.getAccountKey()));
		AMap<AString, ACell> forgedMap = Maps.of(
			UCAN.HEADER, Maps.of(UCAN.ALG, Strings.create("EdDSA"), UCAN.UCV, Strings.create("0.10.0")),
			UCAN.PAYLOAD, forgedPayload,
			UCAN.SIG, token.getSignature()
		);
		UCAN forged = UCAN.parse(forgedMap);
		assertFalse(forged.verifySignature());
		assertNull(UCANValidator.validate(forged, NOW));
	}

	// ===== Chain Validation Tests =====

	@Test
	public void testTwoLinkChain() {
		// Root delegates to Agent A
		UCAN rootToken = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		// Agent A sub-delegates to Agent B, carrying rootToken as proof
		AVector<ACell> proofs = Vectors.of(rootToken.toMap());
		UCAN childToken = UCAN.create(AGENT_A_KP, AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY, null, proofs);

		assertNotNull(UCANValidator.validate(childToken, NOW));
	}

	@Test
	public void testThreeLinkChain() {
		AKeyPair agentCKP = AKeyPair.createSeeded(1004);

		// Root -> Agent A
		UCAN t1 = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		// Agent A -> Agent B (proof: t1)
		UCAN t2 = UCAN.create(AGENT_A_KP, AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY, null, Vectors.of(t1.toMap()));

		// Agent B -> Agent C (proof: t2, which embeds t1)
		UCAN t3 = UCAN.create(AGENT_B_KP, agentCKP.getAccountKey(),
			FUTURE_EXPIRY, null, Vectors.of(t2.toMap()));

		assertNotNull(UCANValidator.validate(t3, NOW));
	}

	@Test
	public void testChainLinkMismatch() {
		// Root delegates to Agent A
		UCAN rootToken = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, null);

		// Agent B tries to use rootToken as proof, but rootToken.aud is Agent A, not Agent B
		AVector<ACell> proofs = Vectors.of(rootToken.toMap());
		UCAN badChild = UCAN.create(AGENT_B_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, null, proofs);

		// Should fail: rootToken.aud (Agent A) != badChild.iss (Agent B)
		assertNull(UCANValidator.validate(badChild, NOW));
	}

	@Test
	public void testProofsMayHaveDifferentValidityPeriods() {
		long shortExpiry = NOW + 600;  // 10 minutes
		long longExpiry = NOW + 7200;  // 2 hours

		// Root grants short-lived token
		UCAN rootToken = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			shortExpiry, null, null);

		// Agent A creates a longer-lived sub-delegation. Both links are valid now.
		AVector<ACell> proofs = Vectors.of(rootToken.toMap());
		UCAN child = UCAN.create(AGENT_A_KP, AGENT_B_KP.getAccountKey(),
			longExpiry, null, proofs);

		assertNotNull(UCANValidator.validate(child, NOW));
	}

	@Test
	public void testFiniteAndNonExpiringProofCombinations() {
		UCAN finiteRoot=UCAN.create(ROOT_KP,AGENT_A_KP.getAccountKey(),FUTURE_EXPIRY,null,null);
		UCAN unboundedChild=UCAN.create(AGENT_A_KP,AGENT_B_KP.getAccountKey(),
			(Long)null,null,Vectors.of(finiteRoot.toMap()));
		assertNotNull(UCANValidator.validate(unboundedChild,NOW));

		UCAN unboundedRoot=UCAN.create(ROOT_KP,AGENT_A_KP.getAccountKey(),(Long)null,null,null);
		UCAN finiteChild=UCAN.create(AGENT_A_KP,AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY,null,Vectors.of(unboundedRoot.toMap()));
		assertNotNull(UCANValidator.validate(finiteChild,NOW));

		UCAN bothUnboundedChild=UCAN.create(AGENT_A_KP,AGENT_B_KP.getAccountKey(),
			(Long)null,null,Vectors.of(unboundedRoot.toMap()));
		assertNotNull(UCANValidator.validate(bothUnboundedChild,NOW));
	}

	// ===== Capability Builder Tests =====

	@Test
	public void testCapabilityCreate() {
		AMap<AString, ACell> cap = Capability.create(
			Capability.resourceURI("account", 42),
			Capability.CONVEX_TRANSFER
		);

		assertEquals(Strings.create("convex:account:#42"), cap.get(Capability.WITH));
		assertEquals(Capability.CONVEX_TRANSFER, cap.get(Capability.CAN));
	}

	@Test
	public void testCapabilityWithCaveats() {
		AMap<AString, ACell> caveats = Maps.of(
			Strings.create("max_amount"), CVMLong.create(1000000000L)
		);
		AMap<AString, ACell> cap = Capability.create(
			Capability.resourceURI("account", 42),
			Capability.CONVEX_TRANSFER,
			caveats
		);

		assertEquals(Strings.create("convex:account:#42"), cap.get(Capability.WITH));
		assertEquals(Capability.CONVEX_TRANSFER, cap.get(Capability.CAN));
		assertNotNull(cap.get(Capability.NB));
	}

	@Test
	public void testResourceURI() {
		assertEquals("convex:account:#42", Capability.resourceURI("account", 42).toString());
		assertEquals("convex:actor:#100", Capability.resourceURI("actor", 100).toString());
	}

	@Test
	public void testCapabilityWildcard() {
		AMap<AString, ACell> cap = Capability.create(
			Capability.resourceURI("account", 42),
			Capability.CONVEX_WILDCARD
		);
		assertEquals(Strings.create("convex/*"), cap.get(Capability.CAN));
	}

	// ===== DID Utility Tests =====

	@Test
	public void testDIDKeyRoundTrip() {
		AccountKey key = ROOT_KP.getAccountKey();
		AString did = UCAN.toDIDKey(key);

		assertTrue(did.toString().startsWith("did:key:z6Mk"));

		AccountKey decoded = UCAN.fromDIDKey(did);
		assertEquals(key, decoded);
	}

	@Test
	public void testDIDKeyInvalid() {
		assertNull(UCAN.fromDIDKey(null));
		assertNull(UCAN.fromDIDKey(Strings.create("not-a-did")));
		assertNull(UCAN.fromDIDKey(Strings.create("did:key:invalid")));
	}

	// ===== JWT Encoding Tests =====

	@Test
	public void testJWTRoundTrip() {
		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create("dlfs://test/drives/home"), Strings.create("dlfs/read"))
		);
		UCAN original = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, caps, null);
		assertFalse(original.getPayload().containsKey(UCAN.UCV),
			"The native token carries its version in the native header");

		// Encode as JWT
		AString jwt = original.toJWT(ROOT_KP);
		assertNotNull(jwt);
		assertTrue(jwt.toString().contains("."), "JWT should have dot-separated parts");

		// Decode from JWT
		UCAN decoded = UCAN.fromJWT(jwt);
		assertNotNull(decoded, "Should parse valid JWT UCAN");
		assertEquals(UCAN.VERSION,decoded.getPayload().get(UCAN.UCV));
		assertEquals(original.getIssuer(), decoded.getIssuer());
		assertEquals(original.getAudience(), decoded.getAudience());
		assertEquals(original.getExpiry(), decoded.getExpiry());
		assertEquals(1, decoded.getCapabilities().count());
	}

	@Test
	public void testNonExpiringJWTRoundTrip() {
		AString jwt=UCAN.createJWT(ROOT_KP,AGENT_A_KP.getAccountKey(),(Long)null,null,null);
		UCAN decoded=UCAN.fromJWT(jwt);

		assertNotNull(decoded);
		assertTrue(decoded.getPayload().containsKey(UCAN.EXP));
		assertNull(decoded.getExpiry());
		assertNotNull(UCANValidator.validateJWT(jwt,NOW));
		assertTrue(JWT.parse(jwt).validateClaims((String)null,null),
			"Generic JWT validation treats exp:null permissively as omitted");
	}

	@Test
	public void testJWTMissingExpiryFails() {
		AMap<AString,ACell> payload=UCAN.buildPayload(ROOT_KP.getAccountKey(),
			AGENT_A_KP.getAccountKey(),FUTURE_EXPIRY,null,null,null,null)
			.assoc(UCAN.UCV,UCAN.VERSION).dissoc(UCAN.EXP);
		assertThrows(IllegalArgumentException.class,()->UCAN.signJWT(payload,ROOT_KP));
		AString jwt=JWT.signPublic(payload,ROOT_KP);

		assertNull(UCAN.parseJWT(jwt));
		assertNull(UCAN.fromJWT(jwt));
		assertNull(UCANValidator.validateJWT(jwt,NOW));
	}

	@Test
	public void testOrdinaryJWTIsNotUCAN() {
		AMap<AString,ACell> claims=Maps.of(
			UCAN.ISS,UCAN.toDIDKey(ROOT_KP.getAccountKey()),
			UCAN.AUD,UCAN.toDIDKey(AGENT_A_KP.getAccountKey()));
		AString jwt=JWT.signPublic(claims,ROOT_KP);

		assertTrue(JWT.parse(jwt).validateClaims((String)null,null));
		assertNull(UCAN.parseJWT(jwt));
		assertNull(UCAN.fromJWT(jwt));
		assertNull(UCANValidator.validateJWT(jwt,NOW));
	}

	@Test
	public void testUnsupportedUCANJWTVersionRejectedEverywhere() {
		AMap<AString,ACell> payload=UCAN.buildPayload(ROOT_KP.getAccountKey(),
			AGENT_A_KP.getAccountKey(),FUTURE_EXPIRY,null,null,null,null)
			.assoc(UCAN.UCV,Strings.create("1.0.0"));
		AString jwt=JWT.signPublic(payload,ROOT_KP);

		assertThrows(IllegalArgumentException.class,()->UCAN.signJWT(payload,ROOT_KP));
		assertNull(UCAN.parseJWT(jwt));
		assertNull(UCAN.fromJWT(jwt));
		assertNull(UCANValidator.validateJWT(jwt,NOW));
	}

	@Test
	public void testJWTCreateShortcut() {
		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create("dlfs://test/drives/docs"), Strings.create("dlfs/write"))
		);
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, caps, null);
		assertNotNull(jwt);

		UCAN parsed = UCAN.fromJWT(jwt);
		assertNotNull(parsed);
		assertEquals(UCAN.toDIDKey(ROOT_KP.getAccountKey()), parsed.getIssuer());
	}

	@Test
	public void testJWTValidation() {
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, null, null);

		UCAN validated = UCANValidator.validateJWT(jwt, NOW);
		assertNotNull(validated, "Valid JWT UCAN should pass validation");
	}

	@Test
	public void testJWTExpiredValidation() {
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), PAST_EXPIRY, null, null);

		assertNull(UCANValidator.validateJWT(jwt, NOW), "Expired JWT UCAN should fail validation");
	}

	@Test
	public void testJWTTamperedPayload() {
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, null, null);

		// Tamper by changing a character in the payload section
		String s = jwt.toString();
		int dot1 = s.indexOf('.');
		int dot2 = s.indexOf('.', dot1 + 1);
		// Flip a character in the payload
		char c = s.charAt(dot1 + 5);
		char flipped = (c == 'A') ? 'B' : 'A';
		String tampered = s.substring(0, dot1 + 5) + flipped + s.substring(dot1 + 6);

		assertNull(UCAN.fromJWT(Strings.create(tampered)), "Tampered JWT should fail signature check");
	}

	@Test
	public void testJWTChainValidation() {
		// Root -> Agent A (JWT)
		AString rootJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, null, null);

		// Agent A -> Agent B, with rootJwt as proof (JWT string in prf)
		AVector<ACell> proofs = Vectors.of(rootJwt);
		AString childJwt = UCAN.createJWT(AGENT_A_KP, AGENT_B_KP.getAccountKey(), FUTURE_EXPIRY, null, proofs);

		UCAN validated = UCANValidator.validateJWT(childJwt, NOW);
		assertNotNull(validated, "Valid JWT chain should pass validation");
	}

	@Test
	public void testJWTChainUsesExecutionTimeValidity() {
		AString finiteRoot=UCAN.createJWT(ROOT_KP,AGENT_A_KP.getAccountKey(),
			NOW+600,null,null);
		AString unboundedChild=UCAN.createJWT(AGENT_A_KP,AGENT_B_KP.getAccountKey(),
			(Long)null,null,Vectors.of(finiteRoot));
		assertNotNull(UCANValidator.validateJWT(unboundedChild,NOW));

		AString unboundedRoot=UCAN.createJWT(ROOT_KP,AGENT_A_KP.getAccountKey(),
			(Long)null,null,null);
		AString finiteChild=UCAN.createJWT(AGENT_A_KP,AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY,null,Vectors.of(unboundedRoot));
		assertNotNull(UCANValidator.validateJWT(finiteChild,NOW));
	}

	@Test
	public void testJWTChainLinkMismatch() {
		// Root -> Agent A
		AString rootJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, null, null);

		// Agent B (not A!) tries to use rootJwt as proof
		AVector<ACell> proofs = Vectors.of(rootJwt);
		AString badChild = UCAN.createJWT(AGENT_B_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, null, proofs);

		assertNull(UCANValidator.validateJWT(badChild, NOW),
			"JWT chain with mismatched link should fail");
	}

	@Test
	public void testJWTFromMalformed() {
		assertNull(UCAN.fromJWT(null));
		assertNull(UCAN.fromJWT(Strings.create("not.a.jwt")));
		assertNull(UCAN.fromJWT(Strings.create("")));
	}

	// ===== Adversarial: issuer spoofing via mismatched kid =====
	//
	// A did:key UCAN's issuer IS its key, so verification must be bound to the `iss`
	// claim — never to the attacker-controlled `kid` header. These tests forge a token
	// that claims iss=ROOT but is actually signed by ROGUE (whose key lands in kid), and
	// assert it is rejected at every layer. They fail against kid-based verification.

	/** A JWT claiming {@code iss=spoofedIssuer} but actually signed by {@code signer} (kid = signer's key). */
	private static AString forgeIssuer(AccountKey spoofedIssuer, AKeyPair signer, AccountKey aud,
			long exp, AVector<ACell> caps) {
		AMap<AString, ACell> claims = UCAN.buildPayload(spoofedIssuer, aud, exp, null, caps, null, null)
			.assoc(UCAN.UCV,UCAN.VERSION);
		return JWT.signPublic(claims, signer); // signs with `signer`; kid header = signer's key
	}

	@Test
	public void testJWTIssuerSpoofRejectedAtParse() {
		AString forged = forgeIssuer(ROOT_KP.getAccountKey(), ROGUE_KP, ROGUE_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/secret", Capability.TOP));
		assertNull(UCAN.fromJWT(forged),
			"JWT claiming iss=ROOT but signed by another key (named in kid) must be rejected");
	}

	@Test
	public void testJWTIssuerSpoofRejectedAtValidate() {
		AString forged = forgeIssuer(ROOT_KP.getAccountKey(), ROGUE_KP, ROGUE_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/secret", Capability.TOP));
		assertNull(UCANValidator.validateJWT(forged, NOW));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testJWTIssuerSpoofDroppedAtTransportBoundary() {
		AString forged = forgeIssuer(ROOT_KP.getAccountKey(), ROGUE_KP, ROGUE_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/secret", Capability.TOP));
		// Forgery alone: nothing passes the trust boundary
		assertNull(UCANValidator.parseTransportUCANs(Vectors.of(forged)),
			"Spoofed-issuer token must not pass the transport trust boundary");

		// Mixed with a genuine ROOT->ROGUE grant: only the genuine token survives, and the
		// forged ROOT capability is never attributed to ROOT through the end-to-end path.
		AString genuine = UCAN.createJWT(ROOT_KP, ROGUE_KP.getAccountKey(), FUTURE_EXPIRY,
			oneCap("w/health", Capability.CRUD_READ), null);
		AVector<ACell> verified = UCANValidator.parseTransportUCANs(Vectors.of(forged, genuine));
		assertNotNull(verified);
		assertEquals(1L, verified.count(), "only the genuinely-signed token survives");

		AString rogueDID = UCAN.toDIDKey(ROGUE_KP.getAccountKey());
		AVector<ACell> caps = UCANValidator.capabilitiesFor(verified, rogueDID, ROOT_DID, NOW);
		assertNotNull(caps);
		assertEquals(1L, caps.count());
		assertFalse(Capability.covers((AMap<AString, ACell>) caps.get(0), "w/secret/x", "crud/write"),
			"forged ROOT-issued capability must not appear");
	}

	@Test
	public void testJWTSpoofedProofInChainRejected() {
		// A genuine child (AGENT_A -> AGENT_B) whose proof is a forged ROOT token (claims
		// iss=ROOT but actually signed by ROGUE). The chain must fail to validate.
		AString forgedProof = forgeIssuer(ROOT_KP.getAccountKey(), ROGUE_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/secret", Capability.TOP));
		AString child = UCAN.createJWT(AGENT_A_KP, AGENT_B_KP.getAccountKey(), FUTURE_EXPIRY,
			null, Vectors.of(forgedProof));
		assertNull(UCANValidator.validateJWT(child, NOW),
			"A chain whose proof spoofs its issuer must be rejected");
	}

	@Test
	public void testGenuineJWTStillVerifiesAfterFix() {
		// Regression: the fix must not break legitimately-signed tokens (kid == iss key).
		AString genuine = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY,
			oneCap("w/health", Capability.CRUD_READ), null);
		UCAN parsed = UCAN.fromJWT(genuine);
		assertNotNull(parsed);
		assertEquals(ROOT_DID, parsed.getIssuer());
		assertNotNull(UCANValidator.validateJWT(genuine, NOW));
	}

	// ===== checkTemporalBounds =====
	//
	// Post-ingress helper used by callers that have already verified the
	// signature and chain and only need to re-guard against expiry between
	// verification and use.

	@Test
	public void testCheckTemporalBoundsNull() {
		assertFalse(UCANValidator.checkTemporalBounds(null, NOW));
	}

	@Test
	public void testCheckTemporalBoundsValid() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), Vectors.empty());
		assertTrue(UCANValidator.checkTemporalBounds(token, NOW));
	}

	@Test
	public void testCheckTemporalBoundsNonExpiring() {
		UCAN token=UCAN.create(ROOT_KP,AGENT_A_KP.getAccountKey(),(Long)null,null,null);
		assertTrue(UCANValidator.checkTemporalBounds(token,NOW));
	}

	@Test
	public void testCheckTemporalBoundsExpired() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			PAST_EXPIRY, Vectors.empty(), Vectors.empty());
		assertFalse(UCANValidator.checkTemporalBounds(token, NOW));
	}

	@Test
	public void testCheckTemporalBoundsExpiryAtBoundary() {
		// exp == now must be treated as expired (strict <=)
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			NOW, Vectors.empty(), Vectors.empty());
		assertFalse(UCANValidator.checkTemporalBounds(token, NOW));
	}

	@Test
	public void testCheckTemporalBoundsNotBeforeInFuture() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, NOW + 600, Vectors.empty(), Vectors.empty());
		assertFalse(UCANValidator.checkTemporalBounds(token, NOW));
	}

	@Test
	public void testCheckTemporalBoundsNotBeforeInPast() {
		UCAN token = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, NOW - 600, Vectors.empty(), Vectors.empty());
		assertTrue(UCANValidator.checkTemporalBounds(token, NOW));
	}

	// ===== capabilitiesFor =====
	//
	// Post-ingress: given already-verified proofs, collect the capabilities
	// granted to a specific audience by a specific (trusted) issuer. The
	// audience/issuer policy is supplied by the caller; selection is fail-closed.

	private static final AString ROOT_DID = UCAN.toDIDKey(ROOT_KP.getAccountKey());
	private static final AString AGENT_A_DID = UCAN.toDIDKey(AGENT_A_KP.getAccountKey());

	/** A verified-shape proof token (as parseTransportUCANs would yield). */
	private static ACell proofToken(AKeyPair iss, AccountKey aud, long exp, AVector<ACell> caps) {
		return UCAN.create(iss, aud, exp, caps, Vectors.empty()).toMap();
	}

	private static AVector<ACell> oneCap(String with, AString can) {
		return Vectors.of((ACell) Capability.create(Strings.create(with), can));
	}

	@Test
	public void testCapabilitiesForNullArgsFailClosed() {
		AVector<ACell> proofs = Vectors.of(proofToken(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/health", Capability.CRUD_READ)));
		assertNull(UCANValidator.capabilitiesFor(null, AGENT_A_DID, ROOT_DID, NOW));
		assertNull(UCANValidator.capabilitiesFor(proofs, null, ROOT_DID, NOW), "null audience fails closed");
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, null, NOW), "null issuer fails closed");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testCapabilitiesForMatching() {
		AVector<ACell> proofs = Vectors.of(proofToken(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/health", Capability.CRUD_READ)));
		AVector<ACell> caps = UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, ROOT_DID, NOW);
		assertNotNull(caps);
		assertEquals(1L, caps.count());
		assertTrue(Capability.covers((AMap<AString, ACell>) caps.get(0), "w/health/bp", "crud/read"));
	}

	@Test
	public void testCapabilitiesForWrongAudienceExcluded() {
		AVector<ACell> proofs = Vectors.of(proofToken(ROOT_KP, AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/health", Capability.CRUD_READ)));
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, ROOT_DID, NOW));
	}

	@Test
	public void testCapabilitiesForWrongIssuerExcluded() {
		AVector<ACell> proofs = Vectors.of(proofToken(ROGUE_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, oneCap("w/health", Capability.CRUD_READ)));
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, ROOT_DID, NOW));
	}

	@Test
	public void testCapabilitiesForExpiredExcluded() {
		AVector<ACell> proofs = Vectors.of(proofToken(ROOT_KP, AGENT_A_KP.getAccountKey(),
			PAST_EXPIRY, oneCap("w/health", Capability.CRUD_READ)));
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, ROOT_DID, NOW));
	}

	@Test
	public void testCapabilitiesForUnionsMatching() {
		AVector<ACell> proofs = Vectors.of(
			proofToken(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, oneCap("w/health", Capability.CRUD_READ)),
			proofToken(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, oneCap("w/notes", Capability.CRUD_WRITE)));
		AVector<ACell> caps = UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, ROOT_DID, NOW);
		assertNotNull(caps);
		assertEquals(2L, caps.count());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testCapabilitiesForMixedKeepsOnlyMatching() {
		AVector<ACell> proofs = Vectors.of(
			proofToken(ROOT_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, oneCap("w/health", Capability.CRUD_READ)),
			proofToken(ROGUE_KP, AGENT_A_KP.getAccountKey(), FUTURE_EXPIRY, oneCap("w/secret", Capability.TOP)));
		AVector<ACell> caps = UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, ROOT_DID, NOW);
		assertNotNull(caps);
		assertEquals(1L, caps.count(), "rogue-issued caps must be excluded");
		assertFalse(Capability.covers((AMap<AString, ACell>) caps.get(0), "w/secret/x", "crud/write"),
			"only the ROOT-issued w/health read survives");
	}

	// ===== parseTransportUCANs =====
	//
	// This is the single trust boundary for inbound transport proofs.
	// Every token in the returned vector must be cryptographically verified.
	// Tokens that fail any check must be silently dropped.

	@Test
	public void testParseTransportUCANsNull() {
		assertNull(UCANValidator.parseTransportUCANs(null));
	}

	@Test
	public void testParseTransportUCANsEmpty() {
		assertNull(UCANValidator.parseTransportUCANs(Vectors.empty()));
	}

	@Test
	public void testParseTransportUCANsValid() {
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AVector<ACell> result = UCANValidator.parseTransportUCANs(Vectors.of(jwt));
		assertNotNull(result);
		assertEquals(1L, result.count());
	}

	@Test
	public void testParseTransportUCANsMalformedDropped() {
		AVector<ACell> result = UCANValidator.parseTransportUCANs(
			Vectors.of(Strings.create("not.a.jwt")));
		assertNull(result, "Malformed JWT must be silently dropped");
	}

	@Test
	public void testParseTransportUCANsExpiredDropped() {
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			PAST_EXPIRY, Vectors.empty(), null);
		assertNull(UCANValidator.parseTransportUCANs(Vectors.of(jwt)),
			"Expired JWT must not pass the trust boundary");
	}

	@Test
	public void testParseTransportUCANsTamperedSignatureDropped() {
		AString jwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);

		// Flip a character in the signature section
		String s = jwt.toString();
		int lastDot = s.lastIndexOf('.');
		char c = s.charAt(lastDot + 1);
		char flipped = (c == 'A') ? 'B' : 'A';
		String tampered = s.substring(0, lastDot + 1) + flipped + s.substring(lastDot + 2);

		assertNull(UCANValidator.parseTransportUCANs(
			Vectors.of(Strings.create(tampered))),
			"Tampered JWT signature must never cross the trust boundary");
	}

	@Test
	public void testParseTransportUCANsMixedValidAndInvalid() {
		AString good = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AString expired = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			PAST_EXPIRY, Vectors.empty(), null);
		AString malformed = Strings.create("not.a.jwt");

		AVector<ACell> result = UCANValidator.parseTransportUCANs(
			Vectors.of(good, expired, malformed));
		assertNotNull(result);
		assertEquals(1L, result.count(),
			"Only the valid token should survive; invalid tokens dropped without error");
	}

	@Test
	public void testParseTransportUCANsNonStringEntryDropped() {
		// Transport should silently drop non-string entries (e.g. accidental map)
		AVector<ACell> result = UCANValidator.parseTransportUCANs(
			Vectors.of(CVMLong.create(42)));
		assertNull(result);
	}

	@Test
	public void testParseTransportUCANsChainValidated() {
		// Valid chain: Root -> A (with root as proof)
		AString rootJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AString childJwt = UCAN.createJWT(AGENT_A_KP, AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), Vectors.of(rootJwt));

		AVector<ACell> result = UCANValidator.parseTransportUCANs(Vectors.of(childJwt));
		assertNotNull(result, "Valid chained JWT should verify");
		assertEquals(1L, result.count());
	}

	@Test
	public void testParseTransportUCANsBrokenChainDropped() {
		// Agent B (wrong subject) tries to use root JWT as proof
		AString rootJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AString badChild = UCAN.createJWT(AGENT_B_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), Vectors.of(rootJwt));

		assertNull(UCANValidator.parseTransportUCANs(Vectors.of(badChild)),
			"Broken chain link must be rejected at the trust boundary");
	}

	// parseTransportUCANsWithBearer — tests

	@Test
	public void testParseTransportUCANsWithBearerBothNull() {
		assertNull(UCANValidator.parseTransportUCANsWithBearer(null, null));
	}

	@Test
	public void testParseTransportUCANsWithBearerOnly() {
		AString bearer = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AVector<ACell> result = UCANValidator.parseTransportUCANsWithBearer(bearer, null);
		assertNotNull(result);
		assertEquals(1L, result.count());
	}

	@Test
	public void testParseTransportUCANsWithBearerBodyOnly() {
		AString bodyJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AVector<ACell> result = UCANValidator.parseTransportUCANsWithBearer(
			null, Vectors.of(bodyJwt));
		assertNotNull(result);
		assertEquals(1L, result.count());
	}

	@Test
	public void testParseTransportUCANsWithBearerMerged() {
		AString bearer = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AString bodyJwt = UCAN.createJWT(ROOT_KP, AGENT_B_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AVector<ACell> result = UCANValidator.parseTransportUCANsWithBearer(
			bearer, Vectors.of(bodyJwt));
		assertNotNull(result);
		assertEquals(2L, result.count(),
			"Both the bearer and the body UCAN should pass the trust boundary");
	}

	@Test
	public void testParseTransportUCANsWithBearerInvalidDropped() {
		// A malformed bearer is silently dropped; a valid body UCAN survives.
		AString bodyJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(),
			FUTURE_EXPIRY, Vectors.empty(), null);
		AVector<ACell> result = UCANValidator.parseTransportUCANsWithBearer(
			Strings.create("not.a.jwt"), Vectors.of(bodyJwt));
		assertNotNull(result);
		assertEquals(1L, result.count());
	}

	@Test
	public void testParseTransportUCANsWithBearerAllInvalid() {
		AVector<ACell> result = UCANValidator.parseTransportUCANsWithBearer(
			Strings.create("not.a.jwt"),
			Vectors.of(Strings.create("also.not.a.jwt")));
		assertNull(result, "No valid tokens means null return (trust boundary empty)");
	}
}
