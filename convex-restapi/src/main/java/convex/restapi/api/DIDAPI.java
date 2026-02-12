package convex.restapi.api;

import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.util.JSON;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DID API serving W3C DID documents for Convex accounts.
 *
 * Supports did:web resolution per the did:web method specification.
 * Three routes:
 * - /.well-known/did.json  — peer's own DID document
 * - /did/{id}/did.json     — account DID via prefix path (option A)
 * - /{id}/did.json         — account DID via suffix catch-all (option B)
 */
public class DIDAPI extends ABaseAPI {

	private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

	public DIDAPI(RESTServer restServer) {
		super(restServer);
	}

	@Override
	public void addRoutes(Javalin app) {
		app.get("/.well-known/did.json", this::handlePeerDID);
		app.get("/did/{identifier}/did.json", this::handleAccountDID);
		app.get("/{identifier}/did.json", this::handleAccountDID);
	}

	/**
	 * Serve the peer's own DID document at /.well-known/did.json
	 */
	protected void handlePeerDID(Context ctx) {
		String hostname = getHostname(ctx);
		String did = "did:web:" + hostname;

		AccountKey peerKey = server.getPeer().getPeerKey();
		Address peerController = server.getPeerController();

		// alsoKnownAs: did:convex address and did:key
		List<String> aliases = new ArrayList<>();
		if (peerController != null) {
			aliases.add("did:convex:" + peerController.longValue());
		}
		aliases.add("did:key:" + multibaseEncodeEd25519DIDKey(peerKey));

		Map<String, Object> doc = buildDIDDocument(did, peerKey, aliases);

		ctx.contentType("application/json");
		ctx.result(JSON.toString(doc));
	}

	/**
	 * Serve a DID document for a specific account
	 */
	protected void handleAccountDID(Context ctx) {
		String identifier = ctx.pathParam("identifier");
		Address address = resolveIdentifier(identifier);
		if (address == null) {
			throw new NotFoundResponse("Account not found: " + identifier);
		}

		AccountStatus as = server.getState().getAccount(address);
		if (as == null) {
			throw new NotFoundResponse("Account not found: " + identifier);
		}

		AccountKey key = as.getAccountKey();

		String hostname = getHostname(ctx);
		String did = "did:web:" + hostname + ":" + identifier;

		// alsoKnownAs: did:convex always, did:key only if account has a key
		List<String> aliases = new ArrayList<>();
		aliases.add("did:convex:" + address.longValue());
		if (key != null) {
			aliases.add("did:key:" + multibaseEncodeEd25519DIDKey(key));
		}

		Map<String, Object> doc = buildDIDDocument(did, key, aliases);

		ctx.contentType("application/json");
		ctx.result(JSON.toString(doc));
	}

	/**
	 * Resolve an identifier string to an Address.
	 * Numeric strings are parsed as account addresses, non-numeric as CNS names.
	 */
	private Address resolveIdentifier(String identifier) {
		if (identifier == null || identifier.isEmpty()) return null;

		// Try numeric address first
		Address addr = Address.parse(identifier);
		if (addr != null) return addr;

		// Try CNS resolution
		try {
			return resolveAddress(Strings.create(identifier));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Build a W3C DID document. Key may be null for actor accounts.
	 */
	private Map<String, Object> buildDIDDocument(String did, AccountKey key, List<String> alsoKnownAs) {
		Map<String, Object> doc = new HashMap<>();
		doc.put("@context", "https://www.w3.org/ns/did/v1");
		doc.put("id", did);
		doc.put("controller", did);

		if (key != null) {
			String keyId = did + "#key-1";
			String multibaseKey = multibaseEncode(key);

			Map<String, Object> verificationMethod = new HashMap<>();
			verificationMethod.put("id", keyId);
			verificationMethod.put("type", "Ed25519VerificationMethod2020");
			verificationMethod.put("controller", did);
			verificationMethod.put("publicKeyMultibase", multibaseKey);

			doc.put("verificationMethod", List.of(verificationMethod));
			doc.put("authentication", List.of(keyId));
		}

		if (alsoKnownAs != null && !alsoKnownAs.isEmpty()) {
			doc.put("alsoKnownAs", alsoKnownAs);
		}

		return doc;
	}

	/**
	 * Encode an Ed25519 public key as multibase base58btc (z prefix).
	 */
	public static String multibaseEncode(AccountKey key) {
		byte[] bytes = key.getBytes();
		return "z" + encodeBase58(bytes);
	}

	/**
	 * Encode an Ed25519 public key as a did:key multibase identifier.
	 * Format: z + base58btc(0xed01 + raw_key_bytes)
	 * The 0xed01 prefix is the multicodec for Ed25519 public keys.
	 */
	public static String multibaseEncodeEd25519DIDKey(AccountKey key) {
		byte[] rawKey = key.getBytes();
		byte[] prefixed = new byte[2 + rawKey.length];
		prefixed[0] = (byte) 0xed;
		prefixed[1] = (byte) 0x01;
		System.arraycopy(rawKey, 0, prefixed, 2, rawKey.length);
		return "z" + encodeBase58(prefixed);
	}

	/**
	 * Base58btc encoding using the Bitcoin alphabet.
	 */
	static String encodeBase58(byte[] input) {
		if (input.length == 0) return "";

		// Count leading zeros
		int zeros = 0;
		while (zeros < input.length && input[zeros] == 0) {
			zeros++;
		}

		// Convert to big integer and encode
		// Work with unsigned bytes
		int[] number = new int[input.length];
		for (int i = 0; i < input.length; i++) {
			number[i] = input[i] & 0xFF;
		}

		char[] encoded = new char[input.length * 2]; // upper bound
		int outputStart = encoded.length;

		int start = zeros;
		while (start < number.length) {
			int remainder = 0;
			int newStart = start;
			boolean started = false;
			for (int i = start; i < number.length; i++) {
				int digit = number[i] + remainder * 256;
				number[i] = digit / 58;
				remainder = digit % 58;
				if (!started && number[i] != 0) {
					newStart = i;
					started = true;
				}
			}
			if (!started) newStart = number.length;
			encoded[--outputStart] = BASE58_ALPHABET.charAt(remainder);
			start = newStart;
		}

		// Add leading '1's for each leading zero byte
		for (int i = 0; i < zeros; i++) {
			encoded[--outputStart] = '1';
		}

		return new String(encoded, outputStart, encoded.length - outputStart);
	}
}
