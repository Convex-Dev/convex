package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.api.DIDAPI;

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

		// verificationMethod must be present
		ACell vm = doc.get(RT.cvm("verificationMethod"));
		assertNotNull(vm, "DID document must have verificationMethod");

		// alsoKnownAs should include did:key
		ACell aka = doc.get(RT.cvm("alsoKnownAs"));
		assertNotNull(aka, "Peer DID should have alsoKnownAs");
		assertTrue(aka.toString().contains("did:key:"), "alsoKnownAs should include did:key");
		assertTrue(aka.toString().contains("did:convex:"), "alsoKnownAs should include did:convex");
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

		// verificationMethod with key
		ACell vm = doc.get(RT.cvm("verificationMethod"));
		assertNotNull(vm);

		// alsoKnownAs
		ACell aka = doc.get(RT.cvm("alsoKnownAs"));
		assertNotNull(aka);
		assertTrue(aka.toString().contains("did:convex:" + addr));
		assertTrue(aka.toString().contains("did:key:"));
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

	@Test
	public void testMultibaseEncoding() {
		// Verify base58btc encoding produces expected format
		AccountKey key = KP.getAccountKey();
		String encoded = DIDAPI.multibaseEncode(key);
		assertTrue(encoded.startsWith("z"), "Multibase base58btc should start with 'z'");
		assertTrue(encoded.length() > 40, "Encoded key should be substantial length");

		// did:key encoding should include multicodec prefix
		String didKeyEncoded = DIDAPI.multibaseEncodeEd25519DIDKey(key);
		assertTrue(didKeyEncoded.startsWith("z"), "did:key multibase should start with 'z'");
		assertTrue(didKeyEncoded.length() > encoded.length(), "did:key encoding includes multicodec prefix");
	}
}
