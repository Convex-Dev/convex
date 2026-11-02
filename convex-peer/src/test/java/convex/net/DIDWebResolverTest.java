package convex.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.did.DIDDocuments;
import convex.auth.did.DIDResolution;
import convex.auth.did.DIDResolver;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.Vectors;

public class DIDWebResolverTest {

	private static final AccountKey KEY=AKeyPair.createSeeded(7101).getAccountKey();
	private static final AString DID_WEB=Strings.create("did:web:files.example.com");

	@Test
	void documentURLFollowsTheMethodSpecification() {
		assertEquals(URI.create("https://w3c-ccg.github.io/.well-known/did.json"),
			DIDWebResolver.documentURL(Strings.create("did:web:w3c-ccg.github.io")));
		assertEquals(URI.create("https://w3c-ccg.github.io/user/alice/did.json"),
			DIDWebResolver.documentURL(Strings.create("did:web:w3c-ccg.github.io:user:alice")));
		assertEquals(URI.create("https://example.com:3000/user/alice/did.json"),
			DIDWebResolver.documentURL(Strings.create("did:web:example.com%3A3000:user:alice")));
	}

	@Test
	void documentURLRefusesWhatIsNotADidWeb() {
		for (String did:new String[] {"did:key:z6Mk","did:web:example.com:","did:web:example.com::x",
				"did:web:user%40example.com","did:web:example.com/path","did:web:"}) {
			assertNull(DIDWebResolver.documentURL(Strings.create(did)),did);
		}
	}

	@Test
	void resolvesTheDocumentAtTheDidsURL() {
		List<URI> fetched=new ArrayList<>();
		DIDWebResolver resolver=new DIDWebResolver(url -> {
			fetched.add(url);
			return respond(200,"application/did+json",document(DID_WEB));
		});

		DIDResolution resolution=resolver.resolve(DID_WEB).join();

		assertNull(resolution.getErrorType(),resolution.toString());
		assertEquals(List.of(URI.create("https://files.example.com/.well-known/did.json")),fetched);
		assertEquals(Vectors.of(KEY),DIDDocuments.authenticationKeys(resolution.getDocument()));
	}

	@Test
	void acceptsJSONMediaTypesOnly() {
		assertNull(resolve(200,"application/json; charset=utf-8",document(DID_WEB)).getErrorType());
		assertNull(resolve(200,"application/did+ld+json",document(DID_WEB)).getErrorType());
		assertEquals(DIDResolution.INVALID_DID_DOCUMENT,resolve(200,"text/html",document(DID_WEB)).getErrorType());
		assertEquals(DIDResolution.INVALID_DID_DOCUMENT,resolve(200,null,document(DID_WEB)).getErrorType());
	}

	@Test
	void refusesADocumentForAnotherDid() {
		DIDResolution resolution=resolve(200,"application/json",document(Strings.create("did:web:other.example.com")));
		assertEquals(DIDResolution.INVALID_DID_DOCUMENT,resolution.getErrorType());
		assertNull(resolution.getDocument());
	}

	@Test
	void refusesMalformedDocuments() {
		assertEquals(DIDResolution.INVALID_DID_DOCUMENT,resolve(200,"application/json","[1,2]").getErrorType());
		assertEquals(DIDResolution.INVALID_DID_DOCUMENT,resolve(200,"application/json","{not json").getErrorType());
		String duplicateServices="""
			{"id": "%1$s", "service": [
			  {"id": "#lattice", "type": "ConvexLatticeNode", "serviceEndpoint": "tcp://a.example:1"},
			  {"id": "%1$s#lattice", "type": "ConvexLatticeNode", "serviceEndpoint": "tcp://b.example:1"}]}
			""".formatted(DID_WEB);
		assertEquals(DIDResolution.INVALID_DID_DOCUMENT,resolve(200,"application/json",duplicateServices).getErrorType());
	}

	@Test
	void mapsHTTPStatusesToResolutionOutcomes() {
		assertEquals(DIDResolution.NOT_FOUND,resolve(404,"text/plain","").getErrorType());
		DIDResolution gone=resolve(410,"application/did-resolution","{}");
		assertTrue(gone.isDeactivated());
		assertNull(gone.getErrorType());
		assertEquals(DIDResolution.INTERNAL_ERROR,resolve(500,"text/plain","").getErrorType());
		// Redirects are not followed, so a redirect is a failure to fetch the document
		assertEquals(DIDResolution.INTERNAL_ERROR,resolve(301,"text/plain","").getErrorType());
	}

	@Test
	void fetchFailuresAreInternalErrors() {
		DIDWebResolver failing=new DIDWebResolver(url -> CompletableFuture.failedFuture(new IllegalStateException("refused")));
		DIDWebResolver throwing=new DIDWebResolver(url -> {throw new IllegalStateException("broken");});
		assertEquals(DIDResolution.INTERNAL_ERROR,failing.resolve(DID_WEB).join().getErrorType());
		assertEquals(DIDResolution.INTERNAL_ERROR,throwing.resolve(DID_WEB).join().getErrorType());
	}

	@Test
	void otherDidsAreInvalidWithoutFetching() {
		DIDWebResolver resolver=new DIDWebResolver(url -> {throw new AssertionError("fetched "+url);});
		assertEquals(DIDResolution.INVALID_DID,resolver.resolve(DID.forKey(KEY)).join().getErrorType());
	}

	@Test
	void composesWithOtherMethods() {
		DIDWebResolver web=new DIDWebResolver(url -> respond(200,"application/json",document(DID_WEB)));
		DIDResolver resolver=DIDResolver.byMethod(Map.of("key",DIDResolver.KEY,"web",web));
		assertNull(resolver.resolve(DID_WEB).join().getErrorType());
		assertNull(resolver.resolve(DID.forKey(KEY)).join().getErrorType());
	}

	@Test
	void boundedBodyCollectsUpToItsBound() throws Exception {
		DIDWebResolver.BoundedBody body=new DIDWebResolver.BoundedBody(8);
		TestSubscription subscription=new TestSubscription();
		body.onSubscribe(subscription);
		body.onNext(List.of(ByteBuffer.wrap("abcd".getBytes(StandardCharsets.UTF_8))));
		body.onNext(List.of(ByteBuffer.wrap("efgh".getBytes(StandardCharsets.UTF_8))));
		body.onComplete();
		assertArrayEquals("abcdefgh".getBytes(StandardCharsets.UTF_8),body.getBody().toCompletableFuture().get());
		assertTrue(!subscription.cancelled);
	}

	@Test
	void boundedBodyFailsPastItsBound() {
		DIDWebResolver.BoundedBody body=new DIDWebResolver.BoundedBody(8);
		TestSubscription subscription=new TestSubscription();
		body.onSubscribe(subscription);
		body.onNext(List.of(ByteBuffer.wrap("abcdefghi".getBytes(StandardCharsets.UTF_8))));
		assertTrue(subscription.cancelled);
		CompletableFuture<byte[]> result=body.getBody().toCompletableFuture();
		assertTrue(result.isCompletedExceptionally());
		try {
			result.get();
		} catch (ExecutionException | InterruptedException e) {
			assertTrue(e.getCause() instanceof IllegalStateException);
		}
	}

	private static DIDResolution resolve(int status,String contentType,String body) {
		return new DIDWebResolver(url -> respond(status,contentType,body)).resolve(DID_WEB).join();
	}

	private static CompletableFuture<DIDWebResolver.Response> respond(int status,String contentType,String body) {
		return CompletableFuture.completedFuture(
			new DIDWebResolver.Response(status,contentType,body.getBytes(StandardCharsets.UTF_8)));
	}

	private static String document(AString did) {
		return """
			{"id": "%1$s",
			 "verificationMethod": [{"id": "%1$s#node", "type": "Multikey", "controller": "%1$s",
			   "publicKeyMultibase": "%2$s"}],
			 "authentication": ["%1$s#node"]}
			""".formatted(did,Multikey.encodePublicKey(KEY));
	}

	private static final class TestSubscription implements Flow.Subscription {
		boolean cancelled;

		@Override
		public void request(long n) {}

		@Override
		public void cancel() {
			cancelled=true;
		}
	}
}
