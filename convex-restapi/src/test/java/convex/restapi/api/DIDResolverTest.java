package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;

class DIDResolverTest {

	private static final State BASE_STATE = Init.createTestState(
			List.of(AKeyPair.createSeeded(618).getAccountKey()));

	@Test
	void resolvesNumericAccountWithoutCNS() {
		String identifier = Long.toString(Init.GENESIS_ADDRESS.longValue());
		String did = "did:web:example.com:" + identifier;

		DIDResolver.Resolution resolution = DIDResolver.resolve(BASE_STATE, identifier, did);

		assertEquals(DIDResolver.Status.ACTIVE, resolution.status());
		assertEquals(did, resolution.document().get("id"));
		assertEquals(did, resolution.document().get("controller"));
		assertTrue(stringList(resolution.document().get("alsoKnownAs"))
				.contains("did:convex:" + Init.GENESIS_ADDRESS.longValue()));
		assertTrue(resolution.document().containsKey("verificationMethod"));
		assertTrue(resolution.document().containsKey("authentication"));
		assertTrue(resolution.documentMetadata().isEmpty());
	}

	@Test
	void resolvesNamedAccountAndUsesCNSController() {
		Context context = context();
		context = createName(context, "convex.did-account-test",
				Init.GENESIS_ADDRESS.toString(), Init.ADMIN_ADDRESS.toString());
		String did = "did:web:example.com:convex.did-account-test";

		DIDResolver.Resolution resolution = DIDResolver.resolve(
				context.getState(), "convex.did-account-test", did);

		assertEquals(DIDResolver.Status.ACTIVE, resolution.status());
		assertEquals(did, resolution.document().get("id"));
		assertEquals("did:convex:" + Init.ADMIN_ADDRESS.longValue(),
				resolution.document().get("controller"));

		List<String> aliases = stringList(resolution.document().get("alsoKnownAs"));
		assertTrue(aliases.contains("did:convex:" + Init.GENESIS_ADDRESS.longValue()));
		assertTrue(aliases.stream().anyMatch(alias -> alias.startsWith("did:key:")));
	}

	@Test
	void resolvesActorAccountWithoutInventingAKey() {
		Context context = context();
		context = createName(context, "convex.did-actor-test",
				Init.REGISTRY_ADDRESS.toString(), Init.ADMIN_ADDRESS.toString());

		DIDResolver.Resolution resolution = DIDResolver.resolve(context.getState(),
				"convex.did-actor-test", "did:web:example.com:convex.did-actor-test");

		assertEquals(DIDResolver.Status.ACTIVE, resolution.status());
		assertFalse(resolution.document().containsKey("verificationMethod"));
		assertFalse(resolution.document().containsKey("authentication"));
		assertEquals(List.of("did:convex:" + Init.REGISTRY_ADDRESS.longValue()),
				resolution.document().get("alsoKnownAs"));
	}

	@Test
	void resolvesRegistryDocumentAndProtectsResolverOwnedFields() {
		String stored = """
				{
				  "@context": "https://example.com/custom-context",
				  "id": "did:example:wrong",
				  "controller": "did:example:wrong-controller",
				  "service": [{
				    "id": "#messages",
				    "type": "MessagingService",
				    "serviceEndpoint": "https://example.com/messages"
				  }]
				}
				""";
		RegistrySetup setup = createRegistryRecord(context(), stored);
		Context context = createName(setup.context(), "convex.did-registry-test",
				scopedDID(setup.id()), Init.ADMIN_ADDRESS.toString());
		String did = "did:web:example.com:convex.did-registry-test";

		DIDResolver.Resolution resolution = DIDResolver.resolve(
				context.getState(), "convex.did-registry-test", did);

		assertEquals(DIDResolver.Status.ACTIVE, resolution.status());
		assertEquals(did, resolution.document().get("id"));
		assertEquals("did:convex:" + Init.ADMIN_ADDRESS.longValue(),
				resolution.document().get("controller"));

		List<?> contexts = assertInstanceOf(List.class, resolution.document().get("@context"));
		assertEquals(DIDResolver.DID_CONTEXT, contexts.get(0));
		assertTrue(contexts.contains("https://example.com/custom-context"));

		List<?> services = assertInstanceOf(List.class, resolution.document().get("service"));
		Map<?, ?> service = assertInstanceOf(Map.class, services.get(0));
		assertEquals("https://example.com/messages", service.get("serviceEndpoint"));

		assertEquals(false, resolution.documentMetadata().get("deactivated"));
		assertTrue(resolution.documentMetadata().get("created").toString().endsWith("Z"));
		assertTrue(resolution.documentMetadata().get("updated").toString().endsWith("Z"));
	}

	@Test
	void resolvesInitialEmptyRegistryDocument() {
		RegistrySetup setup = createRegistryRecord(context(), null);
		Context context = createName(setup.context(), "convex.did-empty-test",
				scopedDID(setup.id()), Init.ADMIN_ADDRESS.toString());
		String did = "did:web:example.com:convex.did-empty-test";

		DIDResolver.Resolution resolution = DIDResolver.resolve(
				context.getState(), "convex.did-empty-test", did);

		assertEquals(DIDResolver.Status.ACTIVE, resolution.status());
		assertEquals(DIDResolver.DID_CONTEXT, resolution.document().get("@context"));
		assertEquals(did, resolution.document().get("id"));
		assertEquals("did:convex:" + Init.ADMIN_ADDRESS.longValue(),
				resolution.document().get("controller"));
	}

	@Test
	void reportsDeactivatedRegistryRecordWithMetadata() {
		RegistrySetup setup = createRegistryRecord(context(), "{}");
		Context context = eval(setup.context(),
				"(call @convex.did (deactivate " + setup.id() + "))");
		context = createName(context, "convex.did-deactivated-test",
				scopedDID(setup.id()), Init.ADMIN_ADDRESS.toString());

		DIDResolver.Resolution resolution = DIDResolver.resolve(context.getState(),
				"convex.did-deactivated-test", "did:web:example.com:convex.did-deactivated-test");

		assertEquals(DIDResolver.Status.DEACTIVATED, resolution.status());
		assertNull(resolution.document());
		assertEquals(true, resolution.documentMetadata().get("deactivated"));
		assertTrue(resolution.documentMetadata().containsKey("created"));
		assertTrue(resolution.documentMetadata().containsKey("updated"));
	}

	@Test
	void complexCNSControllerFallsBackToNamedDID() {
		RegistrySetup setup = createRegistryRecord(context(), "{}");
		String scoped = scopedDID(setup.id());
		Context context = createName(setup.context(), "convex.did-complex-controller-test",
				scoped, scoped);
		String did = "did:web:example.com:convex.did-complex-controller-test";

		DIDResolver.Resolution resolution = DIDResolver.resolve(
				context.getState(), "convex.did-complex-controller-test", did);

		assertEquals(DIDResolver.Status.ACTIVE, resolution.status());
		assertEquals(did, resolution.document().get("controller"));
	}

	@Test
	void missingAndUnsupportedSubjectsFailClosed() {
		DIDResolver.Resolution missing = DIDResolver.resolve(
				BASE_STATE, "convex.missing-did-test", "did:web:example.com:convex.missing-did-test");
		assertEquals(DIDResolver.Status.NOT_FOUND, missing.status());

		Context context = createName(context(), "convex.did-invalid-value-test",
				RT.print(Strings.create("not a DID subject")).toString(), Init.ADMIN_ADDRESS.toString());
		DIDResolver.Resolution invalidValue = DIDResolver.resolve(context.getState(),
				"convex.did-invalid-value-test", "did:web:example.com:convex.did-invalid-value-test");
		assertEquals(DIDResolver.Status.INVALID, invalidValue.status());

		context = createName(context, "convex.did-foreign-registry-test",
				"[" + Init.REGISTRY_ADDRESS + " 1]", Init.ADMIN_ADDRESS.toString());
		DIDResolver.Resolution foreignRegistry = DIDResolver.resolve(context.getState(),
				"convex.did-foreign-registry-test", "did:web:example.com:convex.did-foreign-registry-test");
		assertEquals(DIDResolver.Status.INVALID, foreignRegistry.status());
	}

	@Test
	void malformedStoredDocumentFailsClosed() {
		RegistrySetup setup = createRegistryRecord(context(), "not JSON");
		Context context = createName(setup.context(), "convex.did-malformed-document-test",
				scopedDID(setup.id()), Init.ADMIN_ADDRESS.toString());

		DIDResolver.Resolution resolution = DIDResolver.resolve(context.getState(),
				"convex.did-malformed-document-test",
				"did:web:example.com:convex.did-malformed-document-test");

		assertEquals(DIDResolver.Status.INVALID, resolution.status());
		assertNull(resolution.document());
	}

	private static Context context() {
		return Context.create(BASE_STATE, Init.GOVERNANCE_ADDRESS);
	}

	private static Context createName(Context context, String name, String value, String controller) {
		return eval(context, "(*registry*/create '" + name + " " + value + " " + controller + ")");
	}

	private static RegistrySetup createRegistryRecord(Context context, String storedDocument) {
		context = eval(context, "(call @convex.did (create))");
		CVMLong id = assertInstanceOf(CVMLong.class, context.getResult());

		if (storedDocument != null) {
			AString ddo = Strings.create(storedDocument);
			context = eval(context,
					"(call @convex.did (update " + id + " " + RT.print(ddo) + "))");
		}
		return new RegistrySetup(context, id);
	}

	private static String scopedDID(CVMLong id) {
		return "[@convex.did " + id + "]";
	}

	private static Context eval(Context context, String source) {
		Context result = context.eval(Reader.read(source));
		assertFalse(result.isExceptional(),
				() -> "Unexpected CVM error for " + source + ": " + result.getValue());
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<String> stringList(Object value) {
		return (List<String>) assertInstanceOf(List.class, value);
	}

	private record RegistrySetup(Context context, CVMLong id) {
	}
}
