package convex.auth.ucan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDVerifier;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;

/**
 * Tests for the UCAN authority layer (#635): pluggable DIDVerifier signature
 * verification, per-hop delegation attenuation, and RootAuthorityPolicy root checks.
 */
public class UCANAuthorityTest {

	static final AKeyPair ROOT_KP = AKeyPair.createSeeded(2001);   // resource owner
	static final AKeyPair AGENT_A_KP = AKeyPair.createSeeded(2002);
	static final AKeyPair AGENT_B_KP = AKeyPair.createSeeded(2003);
	static final AKeyPair VENUE_KP = AKeyPair.createSeeded(2004);  // custodial authority
	static final AKeyPair ROGUE_KP = AKeyPair.createSeeded(9998);

	static final AString ROOT_DID = UCAN.toDIDKey(ROOT_KP.getAccountKey());
	static final AString AGENT_A_DID = UCAN.toDIDKey(AGENT_A_KP.getAccountKey());
	static final AString AGENT_B_DID = UCAN.toDIDKey(AGENT_B_KP.getAccountKey());
	static final AString VENUE_DID = UCAN.toDIDKey(VENUE_KP.getAccountKey());
	static final AString ROGUE_DID = UCAN.toDIDKey(ROGUE_KP.getAccountKey());

	// Resource owned (self-sovereignly) by ROOT
	static final AString NOTES = ROOT_DID.append("/w/notes");
	static final AString NOTES_ITEM = ROOT_DID.append("/w/notes/item1");
	static final AString SECRET = ROOT_DID.append("/w/secret");

	static final AString CRUD = Strings.create("crud");
	static final AString READ = Strings.create("crud/read");
	static final AString WRITE = Strings.create("crud/write");

	static final long NOW = System.currentTimeMillis() / 1000;
	static final long EXPIRY = NOW + 3600;

	// ===== Helpers =====

	private static AVector<ACell> caps(ACell... capMaps) {
		return Vectors.of(capMaps);
	}

	private static AMap<AString, ACell> cap(AString with, AString can) {
		return Capability.create(with, can);
	}

	/** Build a native token with an arbitrary issuer DID, signed by {@code signer}. */
	private static UCAN makeToken(AString iss, AString aud, AVector<ACell> att,
			AVector<ACell> prf, AKeyPair signer) {
		AMap<AString, ACell> payload = Maps.of(
			UCAN.ISS, iss,
			UCAN.AUD, aud,
			UCAN.EXP, CVMLong.create(EXPIRY),
			UCAN.NNC, Strings.create("test-nonce"),
			UCAN.ATT, (att != null) ? att : Vectors.empty(),
			UCAN.PRF, (prf != null) ? prf : Vectors.empty());
		ASignature sig = signer.sign(Ref.get(payload).getEncoding());
		return UCAN.fromPayload(payload, sig);
	}

	private static AVector<ACell> present(UCAN... tokens) {
		AVector<ACell> v = Vectors.empty();
		for (UCAN t : tokens) v = v.conj(t.toMap());
		return v;
	}

	// ===== Self-sovereign did:key root =====

	@Test
	public void testSelfSovereignRootAccepted() {
		// ROOT grants AGENT_A crud over its own notes — accepted offline, no state needed
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, CRUD)), null);

		AVector<ACell> proofs = present(grant);
		assertTrue(UCANValidator.isAuthorised(proofs, AGENT_A_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));

		AVector<ACell> found = UCANValidator.capabilitiesFor(proofs, AGENT_A_DID,
			NOTES_ITEM, READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW);
		assertNotNull(found);
		assertEquals(1L, found.count());
	}

	@Test
	public void testWrongSignerRootRejected() {
		// ROGUE (genuinely signing as itself) grants a capability over ROOT's resource
		UCAN grant = UCAN.create(ROGUE_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, CRUD)), null);

		assertFalse(UCANValidator.isAuthorised(present(grant), AGENT_A_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	@Test
	public void testWrongAudienceRejected() {
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, CRUD)), null);
		assertFalse(UCANValidator.isAuthorised(present(grant), AGENT_B_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	// ===== Delegation chains: attenuation enforced per hop =====

	@Test
	public void testDelegatedChainAuthorised() {
		// ROOT -> A: crud over notes; A -> B: read only (proper attenuation)
		UCAN rootGrant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, CRUD)), null);
		UCAN delegated = UCAN.create(AGENT_A_KP, AGENT_B_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), Vectors.of(rootGrant.toMap()));

		AVector<ACell> proofs = present(delegated);
		assertTrue(UCANValidator.isAuthorised(proofs, AGENT_B_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		// B was never delegated write
		assertFalse(UCANValidator.isAuthorised(proofs, AGENT_B_DID, NOTES_ITEM, WRITE,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	@Test
	public void testEscalationRefused() {
		// ROOT grants A read over notes ONLY. A tries to delegate B top over everything
		// ROOT owns — signatures, linkage and root identity are all genuine.
		UCAN rootGrant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), null);
		UCAN escalated = UCAN.create(AGENT_A_KP, AGENT_B_KP.getAccountKey(), EXPIRY,
			caps(cap(ROOT_DID.append("/w/"), Capability.TOP),   // escalated beyond the grant
			     cap(NOTES, READ)),                              // properly attenuated sibling
			Vectors.of(rootGrant.toMap()));

		// The chain is genuine (integrity passes)...
		assertNotNull(UCANValidator.validate(escalated, NOW, DIDVerifier.CONVEX));

		AVector<ACell> proofs = present(escalated);
		// ...but the escalated capability authorises nothing beyond the root grant
		assertFalse(UCANValidator.isAuthorised(proofs, AGENT_B_DID, SECRET, WRITE,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		assertFalse(UCANValidator.isAuthorised(proofs, AGENT_B_DID, NOTES_ITEM, WRITE,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		// ...while the properly-attenuated sibling still works
		assertTrue(UCANValidator.isAuthorised(proofs, AGENT_B_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	// ===== Resources that anchor no authority (fail-closed at the capability) =====

	@Test
	public void testNonDIDResourceGrantsNothingButTokenValid() {
		AString convexResource = Strings.create("convex:account:#42");
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(convexResource, Capability.TOP), cap(NOTES, READ)), null);

		// Token integrity is fine — an unknown resource scheme must not reject the token
		assertNotNull(UCANValidator.validate(grant, NOW, DIDVerifier.CONVEX));

		AVector<ACell> proofs = present(grant);
		// The non-DID capability grants nothing under the self-sovereign policy...
		assertFalse(UCANValidator.isAuthorised(proofs, AGENT_A_DID, convexResource,
			Strings.create("convex/transfer"), RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		// ...while the DID-scoped sibling in the same token still authorises
		assertTrue(UCANValidator.isAuthorised(proofs, AGENT_A_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	@Test
	public void testMalformedDIDResourceFailsClosed() {
		AString malformed = Strings.create("did:%zz bad/w/notes");
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(malformed, Capability.TOP)), null);
		assertFalse(UCANValidator.isAuthorised(present(grant), AGENT_A_DID, malformed,
			READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	// ===== Custodial roots via policy composition =====

	@Test
	public void testCustodialRootAcceptedIffPolicyAccepts() {
		// VENUE (custodian) roots a grant over ROOT's resource
		UCAN grant = UCAN.create(VENUE_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), null);
		AVector<ACell> proofs = present(grant);

		// Self-sovereign alone: refused (venue is not the owner)
		assertFalse(UCANValidator.isAuthorised(proofs, AGENT_A_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));

		// Composed with a custodial policy trusting VENUE: accepted
		RootAuthorityPolicy custodial = RootAuthorityPolicy.SELF_SOVEREIGN
			.or((rootIss, with) -> VENUE_DID.equals(rootIss));
		assertTrue(UCANValidator.isAuthorised(proofs, AGENT_A_DID, NOTES_ITEM, READ,
			custodial, NOW));
		// ...but the custodial policy does not open the door for anyone else
		UCAN rogueGrant = UCAN.create(ROGUE_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), null);
		assertFalse(UCANValidator.isAuthorised(present(rogueGrant), AGENT_A_DID, NOTES_ITEM,
			READ, custodial, NOW));
	}

	// ===== Pluggable verification: non-did:key issuers =====

	@Test
	public void testInjectedVerifierForNonDidKeyIssuer() {
		// A did:web venue signs with a key known to the caller's verifier
		AString webDID = Strings.create("did:web:venue.example.com");
		UCAN grant = makeToken(webDID, Strings.create(AGENT_A_DID.toString()),
			caps(cap(webDID.append("/w/reports"), READ)), null, VENUE_KP);

		// Default did:key verifier cannot verify a did:web issuer
		assertNull(UCANValidator.validate(grant, NOW, DIDVerifier.CONVEX));

		// Composed with a caller-supplied did:web verifier, it verifies
		DIDVerifier webVerifier = (did, message, signature) ->
			webDID.equals(did) && VENUE_KP.getAccountKey() != null
				&& ASignature.fromBlob(signature).verify(message, VENUE_KP.getAccountKey());
		DIDVerifier composed = DIDVerifier.CONVEX.or(webVerifier);
		assertNotNull(UCANValidator.validate(grant, NOW, composed));

		// Self-sovereign: the did:web issuer owns its own DID-scoped resource
		assertTrue(UCANValidator.isAuthorised(present(grant), AGENT_A_DID,
			webDID.append("/w/reports/q1"), READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	@Test
	public void testNonDidKeyIntermediateVerifiedViaInjectedVerifier() {
		// ROOT -> did:web venue -> AGENT_A: the intermediate hop is not did:key
		AString webDID = Strings.create("did:web:venue.example.com");
		UCAN rootGrant = makeToken(ROOT_DID, webDID, caps(cap(NOTES, CRUD)), null, ROOT_KP);
		UCAN delegated = makeToken(webDID, Strings.create(AGENT_A_DID.toString()),
			caps(cap(NOTES, READ)), Vectors.of(rootGrant.toMap()), VENUE_KP);

		DIDVerifier webVerifier = (did, message, signature) ->
			webDID.equals(did) && ASignature.fromBlob(signature).verify(message, VENUE_KP.getAccountKey());
		DIDVerifier composed = DIDVerifier.CONVEX.or(webVerifier);

		// did:key-only verification fails on the intermediate hop
		assertNull(UCANValidator.validate(delegated, NOW, DIDVerifier.CONVEX));
		assertNotNull(UCANValidator.validate(delegated, NOW, composed));

		// Authority: root is the owner, chain is properly attenuated
		assertTrue(UCANValidator.isAuthorised(present(delegated), AGENT_A_DID, NOTES_ITEM,
			READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	// ===== did:convex resolution against CVM state =====

	@Test
	public void testDidConvexRootAgainstState() {
		State state = InitTest.STATE;
		long heroAddr = InitTest.HERO.longValue();
		AString heroDID = Strings.create("did:convex:" + heroAddr);
		// Sanity: the genesis HERO account holds the HERO key
		assertEquals(InitTest.HERO_KEYPAIR.getAccountKey(),
			state.getAccount(InitTest.HERO).getAccountKey());

		UCAN grant = makeToken(heroDID, Strings.create(AGENT_A_DID.toString()),
			caps(cap(heroDID.append("/w/notes"), READ)), null, InitTest.HERO_KEYPAIR);

		// Stateless did:key verifier cannot resolve a did:convex issuer
		assertNull(UCANValidator.validate(grant, NOW, DIDVerifier.CONVEX));

		// State-backed verifier resolves the account key and verifies
		assertNotNull(UCANValidator.validate(grant, NOW, DIDVerifier.forState(state)));
		assertTrue(UCANValidator.isAuthorised(present(grant), AGENT_A_DID,
			heroDID.append("/w/notes/x"), READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	@Test
	public void testDidConvexRejectedAfterKeyRotation() {
		State state = InitTest.STATE;
		AString heroDID = Strings.create("did:convex:" + InitTest.HERO.longValue());
		UCAN grant = makeToken(heroDID, Strings.create(AGENT_A_DID.toString()),
			caps(cap(heroDID.append("/w/notes"), READ)), null, InitTest.HERO_KEYPAIR);
		assertNotNull(UCANValidator.validate(grant, NOW, DIDVerifier.forState(state)));

		// Rotate the account key: the old signature no longer verifies (implicit revocation)
		State rotated = state.putAccount(InitTest.HERO,
			state.getAccount(InitTest.HERO).withAccountKey(ROGUE_KP.getAccountKey()));
		assertNull(UCANValidator.validate(grant, NOW, DIDVerifier.forState(rotated)));
	}

	@Test
	public void testDidConvexUnknownOrNamedRejected() {
		State state = InitTest.STATE;
		DIDVerifier v = DIDVerifier.forState(state);
		Blob msg = Blob.fromHex("cafebabe");
		Blob sig = Blob.wrap(new byte[64]);
		assertFalse(v.verifies(Strings.create("did:convex:999999999"), msg, sig));
		// Named aliases need CNS resolution — not resolvable by the core default
		assertFalse(v.verifies(Strings.create("did:convex:some.name"), msg, sig));
	}

	// ===== JWT transport path =====

	@Test
	public void testJWTTransportWithAuthority() {
		// ROOT -> A (JWT), A -> B (JWT, rootJwt in prf): proofs cross the boundary as
		// JWT strings, so root tracing must handle JWT-encoded prf entries.
		AString rootJwt = UCAN.createJWT(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, CRUD)), null);
		AString childJwt = UCAN.createJWT(AGENT_A_KP, AGENT_B_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), Vectors.of(rootJwt));

		AVector<ACell> verified = UCANValidator.parseTransportUCANs(
			Vectors.of(childJwt), DIDVerifier.CONVEX);
		assertNotNull(verified);
		assertEquals(1L, verified.count());

		assertTrue(UCANValidator.isAuthorised(verified, AGENT_B_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		assertFalse(UCANValidator.isAuthorised(verified, AGENT_B_DID, NOTES_ITEM, WRITE,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	@Test
	public void testJWTDidConvexIssuerViaStateVerifier() {
		State state = InitTest.STATE;
		AString heroDID = Strings.create("did:convex:" + InitTest.HERO.longValue());
		AMap<AString, ACell> claims = Maps.of(
			UCAN.ISS, heroDID,
			UCAN.AUD, AGENT_A_DID,
			UCAN.EXP, CVMLong.create(EXPIRY),
			UCAN.NNC, Strings.create("test-nonce"),
			UCAN.ATT, caps(cap(heroDID.append("/w/notes"), READ)),
			UCAN.PRF, Vectors.empty());
		AString jwt = convex.auth.jwt.JWT.signPublic(claims, InitTest.HERO_KEYPAIR);

		// did:key-only boundary drops it; state-backed verifier admits it
		assertNull(UCANValidator.parseTransportUCANs(Vectors.of(jwt), DIDVerifier.CONVEX));
		AVector<ACell> verified = UCANValidator.parseTransportUCANs(
			Vectors.of(jwt), DIDVerifier.forState(state));
		assertNotNull(verified);
		assertTrue(UCANValidator.isAuthorised(verified, AGENT_A_DID,
			heroDID.append("/w/notes/x"), READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
	}

	// ===== Fail-closed against defective implementations =====

	@Test
	public void testThrowingVerifierFailsClosed() {
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), null);
		DIDVerifier broken = (did, message, signature) -> { throw new RuntimeException("boom"); };
		assertNull(UCANValidator.validate(grant, NOW, broken));
		// Composition survives a throwing left-hand verifier
		assertNotNull(UCANValidator.validate(grant, NOW, broken.or(DIDVerifier.CONVEX)));
	}

	@Test
	public void testThrowingPolicyFailsClosed() {
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), null);
		RootAuthorityPolicy broken = (rootIss, with) -> { throw new RuntimeException("boom"); };
		assertFalse(UCANValidator.isAuthorised(present(grant), AGENT_A_DID, NOTES_ITEM,
			READ, broken, NOW));
		// Composition survives a throwing left-hand policy
		assertTrue(UCANValidator.isAuthorised(present(grant), AGENT_A_DID, NOTES_ITEM,
			READ, broken.or(RootAuthorityPolicy.SELF_SOVEREIGN), NOW));
	}

	@Test
	public void testNullArgumentsFailClosed() {
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(cap(NOTES, READ)), null);
		AVector<ACell> proofs = present(grant);
		assertNull(UCANValidator.capabilitiesFor(null, AGENT_A_DID, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		assertNull(UCANValidator.capabilitiesFor(proofs, null, NOTES_ITEM, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, null, READ,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, NOTES_ITEM, null,
			RootAuthorityPolicy.SELF_SOVEREIGN, NOW));
		assertNull(UCANValidator.capabilitiesFor(proofs, AGENT_A_DID, NOTES_ITEM, READ,
			null, NOW));
		assertNull(UCANValidator.validate(grant, NOW, null));
	}

	// ===== Caveats survive selection =====

	@Test
	public void testCaveatsRetained() {
		AMap<AString, ACell> caveats = Maps.of(Strings.create("max_items"), CVMLong.create(10));
		AMap<AString, ACell> capWithNb = Capability.create(NOTES, READ, caveats);
		UCAN grant = UCAN.create(ROOT_KP, AGENT_A_KP.getAccountKey(), EXPIRY,
			caps(capWithNb), null);

		AVector<ACell> found = UCANValidator.capabilitiesFor(present(grant), AGENT_A_DID,
			NOTES_ITEM, READ, RootAuthorityPolicy.SELF_SOVEREIGN, NOW);
		assertNotNull(found);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> selected = (AMap<AString, ACell>) found.get(0);
		assertNotNull(selected.get(Capability.NB), "caveats must survive selection for enforcement");
	}
}
