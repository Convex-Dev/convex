package convex.net;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import convex.auth.did.DID;
import convex.auth.did.DIDDocuments;
import convex.auth.did.DIDResolution;
import convex.auth.did.DIDResolver;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Resolves {@code did:web} DIDs by fetching their documents over HTTPS, as the did:web
 * method specifies: the DID becomes an {@code https://} URL, and the document found
 * there must be JSON whose {@code id} is the DID.
 *
 * <p>Where the method is silent, this resolver follows no redirects, so the document
 * comes from the origin the DID names, and bounds each fetch in size and time. It does
 * not cache: a DID is fetched each time it is resolved. A {@code 404} is
 * {@link DIDResolution#NOT_FOUND}; a {@code 410}, as DID Resolution's HTTP binding uses
 * it, is a deactivated DID.</p>
 *
 * @see <a href="https://w3c-ccg.github.io/did-method-web/">did:web Method Specification</a>
 */
public class DIDWebResolver implements DIDResolver {

	/** Default bound on the time to fetch a document. */
	public static final Duration DEFAULT_TIMEOUT=Duration.ofSeconds(10);
	/** Default bound on a document's size in bytes. */
	public static final int DEFAULT_MAX_BYTES=64*1024;

	private static final String PREFIX="did:web:";

	/** A fetched document as the resolver reads it. */
	public record Response(int status,String contentType,byte[] body) {}

	/** One HTTPS GET of a document URL. */
	@FunctionalInterface
	public interface Fetcher {
		/**
		 * Fetches a document.
		 *
		 * @param url {@code https://} document URL
		 * @return future completing with the response, or failing if none was received
		 */
		CompletableFuture<Response> fetch(URI url);
	}

	private final Fetcher fetcher;

	/** Creates a resolver with the JVM's TLS defaults and the default bounds. */
	public DIDWebResolver() {
		this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(DEFAULT_TIMEOUT).build(),DEFAULT_TIMEOUT,DEFAULT_MAX_BYTES);
	}

	/**
	 * Creates a resolver fetching with a given client, which sets the TLS context.
	 *
	 * @param client HTTP client; it should not follow redirects
	 * @param timeout bound on each fetch
	 * @param maxBytes bound on each document's size
	 */
	public DIDWebResolver(HttpClient client,Duration timeout,int maxBytes) {
		this(url -> fetch(client,url,timeout,maxBytes));
	}

	/**
	 * Creates a resolver with its own way to fetch documents.
	 *
	 * @param fetcher fetches a document URL
	 */
	public DIDWebResolver(Fetcher fetcher) {
		if (fetcher==null) throw new IllegalArgumentException("A did:web resolver needs a fetcher");
		this.fetcher=fetcher;
	}

	@Override
	public CompletableFuture<DIDResolution> resolve(AString did) {
		URI url=documentURL(did);
		if (url==null) {
			return CompletableFuture.completedFuture(DIDResolution.error(DIDResolution.INVALID_DID,
				"Not a did:web DID: "+did));
		}
		CompletableFuture<Response> fetched;
		try {
			fetched=fetcher.fetch(url);
		} catch (RuntimeException e) {
			fetched=CompletableFuture.failedFuture(e);
		}
		return fetched.handle((response,error) -> {
			if (error!=null) {
				Throwable cause=(error instanceof CompletionException && error.getCause()!=null)?error.getCause():error;
				return DIDResolution.error(DIDResolution.INTERNAL_ERROR,"Fetching "+url+" failed: "+cause.getMessage());
			}
			return read(did,url,response);
		});
	}

	/** Reads a fetched response as the DID's document. */
	static DIDResolution read(AString did,URI url,Response response) {
		int status=response.status();
		if (status==404) return DIDResolution.error(DIDResolution.NOT_FOUND,"No document at "+url);
		if (status==410) return DIDResolution.deactivated(null);
		if (status!=200) {
			return DIDResolution.error(DIDResolution.INTERNAL_ERROR,"HTTP status "+status+" from "+url);
		}
		if (!isJSON(response.contentType())) {
			return DIDResolution.error(DIDResolution.INVALID_DID_DOCUMENT,
				"Document at "+url+" is not JSON: "+response.contentType());
		}
		AMap<AString,ACell> document;
		try {
			document=RT.ensureMap(JSON.parse(new String(response.body(),StandardCharsets.UTF_8)));
		} catch (RuntimeException e) {
			document=null;
		}
		if (document==null) {
			return DIDResolution.error(DIDResolution.INVALID_DID_DOCUMENT,"Document at "+url+" is not a JSON object");
		}
		if (!did.equals(document.get(DIDDocuments.ID))) {
			return DIDResolution.error(DIDResolution.INVALID_DID_DOCUMENT,
				"Document at "+url+" has id "+document.get(DIDDocuments.ID)+", not "+did);
		}
		String problem=DIDDocuments.problem(document);
		if (problem!=null) return DIDResolution.error(DIDResolution.INVALID_DID_DOCUMENT,problem);
		return DIDResolution.of(document,null);
	}

	/**
	 * The URL of a {@code did:web} DID's document: colons become path separators, an
	 * encoded port colon in the host is decoded, {@code /.well-known} stands in for an
	 * empty path, and {@code /did.json} is appended to an {@code https://} URL.
	 *
	 * @param did canonical {@code did:web} DID
	 * @return document URL, or null if the DID is not a well-formed {@code did:web}
	 */
	public static URI documentURL(AString did) {
		if (!DID.isCanonicalBase(did)) return null;
		String text=did.toString();
		if (!text.startsWith(PREFIX)) return null;
		String[] segments=text.substring(PREFIX.length()).split(":",-1);
		String host=segments[0].replace("%3A",":");
		StringBuilder path=new StringBuilder();
		for (int i=1; i<segments.length; i++) {
			if (segments[i].isEmpty()) return null;
			path.append('/').append(segments[i]);
		}
		if (path.length()==0) path.append("/.well-known");
		path.append("/did.json");
		try {
			URI url=new URI("https://"+host+path);
			if (url.getHost()==null || url.getRawUserInfo()!=null || !url.getRawAuthority().equals(host)) return null;
			return url;
		} catch (URISyntaxException e) {
			return null;
		}
	}

	/** JSON media types: {@code application/json} and any {@code +json} type. */
	private static boolean isJSON(String contentType) {
		if (contentType==null) return false;
		int semicolon=contentType.indexOf(';');
		String type=((semicolon<0)?contentType:contentType.substring(0,semicolon)).trim().toLowerCase(Locale.ROOT);
		return type.equals("application/json") || (type.startsWith("application/") && type.endsWith("+json"));
	}

	private static CompletableFuture<Response> fetch(HttpClient client,URI url,Duration timeout,int maxBytes) {
		HttpRequest request=HttpRequest.newBuilder(url).timeout(timeout).GET()
			.header("Accept","application/did+json, application/json").build();
		HttpResponse.BodyHandler<byte[]> bounded=info -> new BoundedBody(maxBytes);
		return client.sendAsync(request,bounded)
			.orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS)
			.thenApply(response -> new Response(response.statusCode(),
				response.headers().firstValue("Content-Type").orElse(null),response.body()));
	}

	/** Collects a body up to a size bound, failing once the bound is passed. */
	static final class BoundedBody implements BodySubscriber<byte[]> {
		private final int maxBytes;
		private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> result=new CompletableFuture<>();
		private Flow.Subscription subscription;

		BoundedBody(int maxBytes) {
			this.maxBytes=maxBytes;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription=subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (result.isDone()) return;
			for (ByteBuffer buffer:buffers) {
				if (bytes.size()+buffer.remaining()>maxBytes) {
					subscription.cancel();
					result.completeExceptionally(new IllegalStateException("Document exceeds "+maxBytes+" bytes"));
					return;
				}
				byte[] chunk=new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
		}

		@Override
		public void onError(Throwable error) {
			result.completeExceptionally(error);
		}

		@Override
		public void onComplete() {
			result.complete(bytes.toByteArray());
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}
	}
}
