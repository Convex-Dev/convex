package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDDocuments;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Vectors;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.util.JSON;

public class DIDAPITest extends ARESTTest {

	@Test
	public void testPeerDID() throws IOException, InterruptedException {
		HttpResponse<String> res = get(HOST_PATH + "/.well-known/did.json");
		assertEquals(200, res.statusCode());

		ACell parsed = JSON.parse(res.body());
		assertNotNull(parsed);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> doc = (AMap<AString, ACell>) parsed;

		// id should be did:web:<host> with no path
		ACell id = doc.get(RT.cvm("id"));
		assertNotNull(id, "DID document must have an id");
		assertTrue(id.toString().startsWith("did:web:"), "Peer DID should start with did:web:");
		assertFalse(id.toString().contains(":did:"), "Peer DID should not have path components");

		// The peer key is a standard Multikey verification method under authentication
		assertNull(DIDDocuments.problem(doc));
		assertEquals(Vectors.of(KP.getAccountKey()), DIDDocuments.authenticationKeys(doc));
		assertKeyIsMultikey(doc, KP.getAccountKey());

		// The key is in the verification method, and the peer's controller is not the peer
		assertNull(doc.get(RT.cvm("alsoKnownAs")), "Peer DID should have no alsoKnownAs");
	}

	@Test
	public void testAccountDIDPrefix() throws IOException, InterruptedException {
		long addr = Init.GENESIS_ADDRESS.longValue();
		HttpResponse<String> res = get(HOST_PATH + "/did/" + addr + "/did.json");
		assertEquals(200, res.statusCode());

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> doc = (AMap<AString, ACell>) JSON.parse(res.body());

		ACell id = doc.get(RT.cvm("id"));
		assertNotNull(id);
		assertTrue(id.toString().contains(":" + addr), "DID should contain account address");
		assertTrue(id.toString().startsWith("did:web:"));

		// verificationMethod with the account key
		AccountKey key = server.getServer().getPeer().getConsensusState().getAccount(Init.GENESIS_ADDRESS).getAccountKey();
		assertEquals(Vectors.of(key), DIDDocuments.authenticationKeys(doc));
		assertKeyIsMultikey(doc, key);

		// alsoKnownAs pins the account; the key is given by the verification method only
		ACell aka = doc.get(RT.cvm("alsoKnownAs"));
		assertEquals(Vectors.of(RT.cvm("did:convex:" + addr)), aka);
	}

	@Test
	public void testAccountDIDSuffix() throws IOException, InterruptedException {
		long addr = Init.GENESIS_ADDRESS.longValue();
		// Option B: suffix catch-all /{id}/did.json
		HttpResponse<String> res = get(HOST_PATH + "/" + addr + "/did.json");
		assertEquals(200, res.statusCode());

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> doc = (AMap<AString, ACell>) JSON.parse(res.body());

		ACell id = doc.get(RT.cvm("id"));
		assertNotNull(id);
		assertTrue(id.toString().contains(":" + addr));
	}

	@Test
	public void testAccountNotFound() throws IOException, InterruptedException {
		HttpResponse<String> res = get(HOST_PATH + "/did/999999999/did.json");
		assertEquals(404, res.statusCode());
	}

	@Test
	public void testActorDID() throws IOException, InterruptedException {
		// Account #9 is an actor with no key
		HttpResponse<String> res = get(HOST_PATH + "/did/9/did.json");
		assertEquals(200, res.statusCode());

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> doc = (AMap<AString, ACell>) JSON.parse(res.body());

		// Should have id and controller
		ACell id = doc.get(RT.cvm("id"));
		assertNotNull(id);
		assertTrue(id.toString().contains(":9"));

		// Should have alsoKnownAs with did:convex but NOT did:key (no key)
		ACell aka = doc.get(RT.cvm("alsoKnownAs"));
		assertNotNull(aka);
		assertTrue(aka.toString().contains("did:convex:9"));
		assertFalse(aka.toString().contains("did:key:"), "Actor without key should not have did:key alias");

		// Should NOT have verificationMethod (no key)
		ACell vm = doc.get(RT.cvm("verificationMethod"));
		assertNull(vm, "Actor without key should not have verificationMethod");
	}

	/** The document's one verification method is a Multikey identified by the encoded key. */
	private static void assertKeyIsMultikey(AMap<AString, ACell> doc, AccountKey key) {
		AString did = RT.ensureString(doc.get(RT.cvm("id")));
		AMap<AString, ACell> method = RT.ensureMap(RT.ensureVector(doc.get(RT.cvm("verificationMethod"))).get(0));
		AString encoded = Multikey.encodePublicKey(key);
		assertEquals(RT.cvm("Multikey"), method.get(RT.cvm("type")));
		assertEquals(encoded, method.get(RT.cvm("publicKeyMultibase")));
		assertEquals(did.append("#").append(encoded), method.get(RT.cvm("id")));
		assertTrue(doc.get(RT.cvm("@context")).toString().contains("https://w3id.org/security/multikey/v1"));
	}
}
