package convex.auth.did;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Base58;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;

public class DIDResolverTest {

	private static final AccountKey KEY=AKeyPair.createSeeded(7001).getAccountKey();
	private static final AccountKey OTHER=AKeyPair.createSeeded(7002).getAccountKey();
	private static final String WEB="did:web:files.example.com";
	private static final AString LATTICE=Strings.create("ConvexLatticeNode");

	@Test
	void didKeyResolvesToTheKeyItNames() {
		AString did=DID.forKey(KEY);
		DIDResolution resolution=DIDResolver.KEY.resolve(did).join();

		assertNull(resolution.getErrorType());
		AMap<AString,ACell> document=resolution.getDocument();
		assertEquals(did,document.get(DIDDocuments.ID));
		assertNull(DIDDocuments.problem(document));
		assertEquals(Vectors.of(KEY),DIDDocuments.authenticationKeys(document));
	}

	@Test
	void didKeyDocumentMatchesTheSpecificationExample() {
		AString did=Strings.create("did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK");
		AMap<AString,ACell> document=DIDKey.resolve(did).getDocument();

		AMap<AString,ACell> method=RT.ensureMap(RT.ensureVector(document.get(DIDDocuments.VERIFICATION_METHOD)).get(0));
		AString methodID=Strings.create(did+"#z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK");
		assertEquals(methodID,method.get(DIDDocuments.ID));
		assertEquals(Strings.create("Multikey"),method.get(DIDDocuments.TYPE));
		assertEquals(did,method.get(DIDDocuments.CONTROLLER));
		assertEquals(Strings.create("z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK"),
			method.get(DIDDocuments.PUBLIC_KEY_MULTIBASE));
		for (AString relationship:new AString[] {DIDDocuments.AUTHENTICATION,DIDDocuments.ASSERTION_METHOD,
				DIDDocuments.CAPABILITY_INVOCATION,DIDDocuments.CAPABILITY_DELEGATION}) {
			assertEquals(Vectors.of(methodID),document.get(relationship));
		}
		assertFalse(document.containsKey(DIDDocuments.KEY_AGREEMENT));
	}

	@Test
	void malformedDidKeysAreInvalid() {
		String bareKey="z"+Base58.encode(KEY.getBytes());
		for (String did:new String[] {"did:key:zabc","did:key:"+bareKey,"did:key:"+KEY.toHexString()}) {
			assertEquals(DIDResolution.INVALID_DID,DIDKey.resolve(Strings.create(did)).getErrorType(),did);
		}
	}

	@Test
	void composedResolverDispatchesByMethod() {
		DIDResolver web=did -> CompletableFuture.completedFuture(DIDResolution.of(document(did.toString(),""),null));
		DIDResolver resolver=DIDResolver.byMethod(Map.of("key",DIDResolver.KEY,"web",web));

		assertNull(resolver.resolve(DID.forKey(KEY)).join().getErrorType());
		assertEquals(Strings.create(WEB),resolver.resolve(Strings.create(WEB)).join().getDocument().get(DIDDocuments.ID));
		assertEquals(DIDResolution.METHOD_NOT_SUPPORTED,
			resolver.resolve(Strings.create("did:example:123")).join().getErrorType());
	}

	@Test
	void composedResolverRefusesWhatIsNotACanonicalDid() {
		DIDResolver resolver=DIDResolver.byMethod(Map.of("key",DIDResolver.KEY));
		for (String did:new String[] {"not-a-did","did:key:","did:KEY:z6Mk","did:web:example.com/path",
				"did:web:example.com#frag","did:web:example.com%3a8080"}) {
			assertEquals(DIDResolution.INVALID_DID,resolver.resolve(Strings.create(did)).join().getErrorType(),did);
		}
	}

	@Test
	void composedResolverTurnsResolverFailuresIntoInternalErrors() {
		DIDResolver throwing=did -> {throw new IllegalStateException("boom");};
		DIDResolver failing=did -> CompletableFuture.failedFuture(new IllegalStateException("down"));
		DIDResolver absent=did -> null;
		AString did=Strings.create("did:example:1");
		for (DIDResolver method:new DIDResolver[] {throwing,failing,absent}) {
			DIDResolution resolution=DIDResolver.byMethod(Map.of("example",method)).resolve(did).join();
			assertEquals(DIDResolution.INTERNAL_ERROR,resolution.getErrorType());
			assertNull(resolution.getDocument());
		}
		DIDResolution failed=DIDResolver.byMethod(Map.of("example",failing)).resolve(did).join();
		assertEquals("down",failed.getErrorDetail());
	}

	@Test
	void deactivatedResolutionHasNoDocument() {
		DIDResolution resolution=DIDResolution.deactivated(null);
		assertTrue(resolution.isDeactivated());
		assertNull(resolution.getDocument());
		assertNull(resolution.getErrorType());
	}

	@Test
	void authenticationKeysAcceptTheStandardKeyTypes() {
		String x=Base64.getUrlEncoder().withoutPadding().encodeToString(OTHER.getBytes());
		AMap<AString,ACell> document=document(WEB,"""
			"verificationMethod": [
			  {"id": "#node", "type": "Multikey", "controller": "%1$s", "publicKeyMultibase": "%2$s"},
			  {"id": "%1$s#jwk", "type": "JsonWebKey", "controller": "%1$s",
			   "publicKeyJwk": {"kty": "OKP", "crv": "Ed25519", "x": "%3$s"}}
			],
			"authentication": ["#node", "%1$s#jwk", "%1$s#node"]
			""".formatted(WEB,multikey(KEY),x));

		assertNull(DIDDocuments.problem(document));
		assertEquals(Vectors.of(KEY,OTHER),DIDDocuments.authenticationKeys(document));
	}

	@Test
	void authenticationKeysAcceptEmbeddedAndOlderTypes() {
		AMap<AString,ACell> document=document(WEB,"""
			"authentication": [
			  {"id": "#a", "type": "Ed25519VerificationKey2020", "controller": "%1$s", "publicKeyMultibase": "%2$s"}
			]
			""".formatted(WEB,multikey(KEY)));
		assertEquals(Vectors.of(KEY),DIDDocuments.authenticationKeys(document));
	}

	@Test
	void authenticationKeysIgnoreWhatTheRuleExcludes() {
		String withPrivate=Base64.getUrlEncoder().withoutPadding().encodeToString(KEY.getBytes());
		AMap<AString,ACell> document=document(WEB,"""
			"verificationMethod": [
			  {"id": "#unlisted", "type": "Multikey", "controller": "%1$s", "publicKeyMultibase": "%2$s"},
			  {"id": "#foreign", "type": "Multikey", "controller": "did:web:other.example", "publicKeyMultibase": "%2$s"},
			  {"id": "#unknown", "type": "EcdsaSecp256k1VerificationKey2019", "controller": "%1$s", "publicKeyMultibase": "%2$s"},
			  {"id": "#bare", "type": "Multikey", "controller": "%1$s", "publicKeyMultibase": "%3$s"},
			  {"id": "#private", "type": "JsonWebKey", "controller": "%1$s",
			   "publicKeyJwk": {"kty": "OKP", "crv": "Ed25519", "x": "%4$s", "d": "%4$s"}}
			],
			"assertionMethod": ["#unlisted"],
			"authentication": ["#foreign", "#unknown", "#bare", "#private", "did:web:other.example#node", "#missing"]
			""".formatted(WEB,multikey(KEY),"z"+Base58.encode(KEY.getBytes()),withPrivate));

		assertNull(DIDDocuments.problem(document));
		assertTrue(DIDDocuments.authenticationKeys(document).isEmpty());
	}

	@Test
	void problemFindsDuplicateIdsAndAMissingId() {
		String method="{\"id\": \"#k\", \"type\": \"Multikey\", \"controller\": \"%s\", \"publicKeyMultibase\": \"%s\"}"
			.formatted(WEB,multikey(KEY));
		assertNotNull(DIDDocuments.problem(document(WEB,"""
			"verificationMethod": [%1$s], "authentication": [%1$s]
			""".formatted(method))));
		assertNotNull(DIDDocuments.problem(document(WEB,"""
			"service": [
			  {"id": "#lattice", "type": "ConvexLatticeNode", "serviceEndpoint": "tcp://a.example:1"},
			  {"id": "%s#lattice", "type": "ConvexLatticeNode", "serviceEndpoint": "tcp://b.example:1"}
			]
			""".formatted(WEB))));
		AMap<AString,ACell> noID=RT.ensureMap(JSON.parse("{\"service\": []}"));
		assertNotNull(DIDDocuments.problem(noID));
	}

	@Test
	void serviceEndpointsSelectByTypeAndSkipMaps() {
		AMap<AString,ACell> document=document(WEB,"""
			"service": [
			  {"id": "#a", "type": "ConvexLatticeNode", "serviceEndpoint": ["tls://a.example:19888", {"uri": "tcp://x"}, "tcp://a.example:19888"]},
			  {"id": "#b", "type": ["LinkedDomains", "ConvexLatticeNode"], "serviceEndpoint": "tcp://b.example:19888"},
			  {"id": "#c", "type": "ConvexLatticeNode", "serviceEndpoint": {"uri": "tcp://c.example:19888"}},
			  {"id": "#d", "type": "LinkedDomains", "serviceEndpoint": "https://files.example.com"}
			]
			""");

		assertNull(DIDDocuments.problem(document));
		AVector<AString> expected=Vectors.of(Strings.create("tls://a.example:19888"),
			Strings.create("tcp://a.example:19888"),Strings.create("tcp://b.example:19888"));
		assertEquals(expected,DIDDocuments.serviceEndpoints(document,LATTICE));
		assertTrue(DIDDocuments.serviceEndpoints(document(WEB,""),LATTICE).isEmpty());
	}

	/** A document with the given id and further JSON members. */
	private static AMap<AString,ACell> document(String did,String members) {
		String body=members.isBlank()?"":", "+members;
		return RT.ensureMap(JSON.parse("{\"id\": \""+did+"\""+body+"}"));
	}

	private static String multikey(AccountKey key) {
		return Multikey.encodePublicKey(key).toString();
	}
}
