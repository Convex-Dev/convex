package convex.restapi.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import convex.auth.did.DID;
import convex.core.crypto.util.Base58;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Resolves account and named Convex DIDs against one immutable state snapshot.
 *
 * <p>This class deliberately contains no HTTP behaviour. The result retains DID
 * document metadata for a future native resolver endpoint, while {@link DIDAPI}
 * adapts the status to the existing {@code did:web} routes.</p>
 */
final class DIDResolver {

	static final String DID_CONTEXT = "https://www.w3.org/ns/did/v1";

	private static final Symbol DID_REGISTRY_NAME = Symbol.create("convex.did");
	private static final Symbol READ_RECORD = Symbol.create("read-record");

	enum Status {
		ACTIVE,
		NOT_FOUND,
		DEACTIVATED,
		INVALID
	}

	record Resolution(Status status, Map<String, Object> document,
			Map<String, Object> documentMetadata, String reason) {

		static Resolution active(Map<String, Object> document, Map<String, Object> metadata) {
			return new Resolution(Status.ACTIVE, document, metadata, null);
		}

		static Resolution notFound(String reason) {
			return new Resolution(Status.NOT_FOUND, null, Map.of(), reason);
		}

		static Resolution deactivated(Map<String, Object> metadata) {
			return new Resolution(Status.DEACTIVATED, null, metadata, "DID is deactivated");
		}

		static Resolution invalid(String reason) {
			return new Resolution(Status.INVALID, null, Map.of(), reason);
		}
	}

	private DIDResolver() {
	}

	/**
	 * Resolves an account number or CNS name to a DID document.
	 *
	 * @param state State snapshot used for the complete resolution
	 * @param identifier Numeric account number or CNS name
	 * @param documentDID DID that the returned document must identify
	 * @return Resolution result
	 */
	static Resolution resolve(State state, String identifier, String documentDID) {
		if ((state == null) || (identifier == null) || identifier.isEmpty()
				|| (documentDID == null) || documentDID.isEmpty()) {
			return Resolution.invalid("Missing DID resolution input");
		}

		Address address = Address.parse(identifier);
		if (address != null) {
			return resolveAccount(state, address, documentDID, null);
		}

		Symbol name = Symbol.create(identifier);
		if (name == null) return Resolution.invalid("Invalid CNS name");

		AVector<ACell> cnsRecord = state.lookupCNSRecord(name);
		if (cnsRecord == null) return Resolution.notFound("CNS name does not exist");
		if (cnsRecord.count() != 4) return Resolution.invalid("Malformed CNS record");

		ACell value = cnsRecord.get(0);
		ACell cnsController = cnsRecord.get(1);

		if (value instanceof Address account) {
			return resolveAccount(state, account, documentDID, cnsController);
		}

		if (value instanceof AVector<?> scoped) {
			return resolveRegistryRecord(state, scoped, documentDID, cnsController);
		}

		return Resolution.invalid("CNS value is not a DID subject");
	}

	private static Resolution resolveAccount(State state, Address address, String documentDID, ACell cnsController) {
		AccountStatus account = state.getAccount(address);
		if (account == null) return Resolution.notFound("Account does not exist");

		AccountKey key = account.getAccountKey();
		Map<String, Object> document = baseDocument(documentDID, controllerDID(cnsController, documentDID));

		List<String> aliases = new ArrayList<>();
		aliases.add("did:convex:" + address.longValue());
		if (key != null) {
			aliases.add(DID.forKey(key).toString());
			addVerificationMethod(document, documentDID, key);
		}
		document.put("alsoKnownAs", aliases);

		return Resolution.active(document, Map.of());
	}

	private static Resolution resolveRegistryRecord(State state, AVector<?> scoped, String documentDID,
			ACell cnsController) {
		if (scoped.count() != 2) return Resolution.invalid("Malformed scoped DID registry reference");

		Address registryAddress = RT.ensureAddress(scoped.get(0));
		if (registryAddress == null) return Resolution.invalid("DID registry reference has no actor address");

		Address expectedRegistry = RT.ensureAddress(state.lookupCNS(DID_REGISTRY_NAME));
		if ((expectedRegistry == null) || !expectedRegistry.equals(registryAddress)) {
			return Resolution.invalid("Unsupported DID registry actor");
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> target = (AVector<ACell>) scoped;
		Context result = Context.create(state).actorCall(target, 0, READ_RECORD);
		if (result.isExceptional()) return Resolution.invalid("DID registry read failed");

		AVector<ACell> record = RT.ensureVector(result.getResult());
		if (record == null) return Resolution.notFound("DID registry record does not exist");
		if (record.count() != 5) return Resolution.invalid("Malformed DID registry record");

		String created = timestamp(record.get(2));
		String updated = timestamp(record.get(3));
		if ((created == null) || (updated == null)) {
			return Resolution.invalid("DID registry record has invalid timestamps");
		}

		boolean deactivated = record.get(0) == null;
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("created", created);
		metadata.put("updated", updated);
		metadata.put("deactivated", deactivated);

		if (deactivated) return Resolution.deactivated(metadata);

		AString storedDocument = RT.ensureString(record.get(0));
		if (storedDocument == null) return Resolution.invalid("Stored DID document is not a string");

		Map<String, Object> document = parseStoredDocument(storedDocument);
		if (document == null) return Resolution.invalid("Stored DID document is not a JSON object");

		ensureDIDContext(document);
		document.put("id", documentDID);
		document.put("controller", controllerDID(cnsController, documentDID));

		return Resolution.active(document, metadata);
	}

	private static Map<String, Object> parseStoredDocument(AString storedDocument) {
		if (storedDocument.toString().trim().isEmpty()) return new HashMap<>();

		try {
			ACell parsed = JSON.parse(storedDocument);
			if (!(parsed instanceof AMap<?, ?> map)) return null;
			return new HashMap<>(JSON.jsonMap(map));
		} catch (Exception e) {
			return null;
		}
	}

	private static void ensureDIDContext(Map<String, Object> document) {
		Object context = document.get("@context");
		if (context == null) {
			document.put("@context", DID_CONTEXT);
			return;
		}
		if (DID_CONTEXT.equals(context)) return;

		if (context instanceof List<?> contexts) {
			if (contexts.contains(DID_CONTEXT)) return;
			ArrayList<Object> combined = new ArrayList<>(contexts.size() + 1);
			combined.add(DID_CONTEXT);
			combined.addAll(contexts);
			document.put("@context", combined);
			return;
		}

		document.put("@context", List.of(DID_CONTEXT, context));
	}

	private static Map<String, Object> baseDocument(String did, String controller) {
		Map<String, Object> document = new HashMap<>();
		document.put("@context", DID_CONTEXT);
		document.put("id", did);
		document.put("controller", controller);
		return document;
	}

	private static String controllerDID(ACell controller, String fallbackDID) {
		if (controller instanceof Address address) return "did:convex:" + address.longValue();
		return fallbackDID;
	}

	private static void addVerificationMethod(Map<String, Object> document, String did, AccountKey key) {
		String keyID = did + "#key-1";

		Map<String, Object> verificationMethod = new HashMap<>();
		verificationMethod.put("id", keyID);
		verificationMethod.put("type", "Ed25519VerificationMethod2020");
		verificationMethod.put("controller", did);
		verificationMethod.put("publicKeyMultibase", "z" + Base58.encode(key.getBytes()));

		document.put("verificationMethod", List.of(verificationMethod));
		document.put("authentication", List.of(keyID));
	}

	private static String timestamp(ACell value) {
		if (!(value instanceof CVMLong timestamp)) return null;
		try {
			return Instant.ofEpochMilli(timestamp.longValue()).truncatedTo(ChronoUnit.SECONDS).toString();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
