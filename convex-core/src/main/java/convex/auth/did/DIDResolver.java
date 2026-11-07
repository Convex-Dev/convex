package convex.auth.did;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import convex.core.data.AString;

/**
 * Resolves a DID to its document, as the {@code resolve} function of W3C DID Resolution
 * does. One interface serves every DID method; resolvers for different methods compose
 * with {@link #byMethod(Map)}.
 *
 * <p>The DID must be a base DID in the canonical form {@link DID#isCanonicalBase}
 * accepts. Resolution never throws and the returned future never fails: a DID that
 * cannot be resolved gives a {@link DIDResolution} carrying a DID Resolution error.
 * Resolution options are not supported.</p>
 *
 * @see <a href="https://www.w3.org/TR/did-resolution/">W3C DID Resolution</a>
 */
@FunctionalInterface
public interface DIDResolver {

	/**
	 * Resolves a DID.
	 *
	 * @param did canonical base DID
	 * @return future completing with the resolution, never failing
	 */
	CompletableFuture<DIDResolution> resolve(AString did);

	/** Resolves {@code did:key} DIDs for Ed25519 keys, without network access. */
	DIDResolver KEY=did -> CompletableFuture.completedFuture(DIDKey.resolve(did));

	/**
	 * Composes resolvers by DID method. A DID that is not canonical gives
	 * {@link DIDResolution#INVALID_DID}; a method with no resolver gives
	 * {@link DIDResolution#METHOD_NOT_SUPPORTED}; a resolver that throws or fails its
	 * future gives {@link DIDResolution#INTERNAL_ERROR}.
	 *
	 * @param methods resolver for each method name, such as {@code "key"} or {@code "web"}
	 * @return composed resolver
	 */
	static DIDResolver byMethod(Map<String,DIDResolver> methods) {
		Map<String,DIDResolver> resolvers=Map.copyOf(methods);
		return did -> {
			if (!DID.isCanonicalBase(did)) {
				return CompletableFuture.completedFuture(DIDResolution.error(DIDResolution.INVALID_DID,
					"Not a canonical DID: "+did));
			}
			String text=did.toString();
			String method=text.substring(4,text.indexOf(':',4));
			DIDResolver resolver=resolvers.get(method);
			if (resolver==null) {
				return CompletableFuture.completedFuture(DIDResolution.error(DIDResolution.METHOD_NOT_SUPPORTED,
					"No resolver for did:"+method));
			}
			CompletableFuture<DIDResolution> result;
			try {
				result=resolver.resolve(did);
			} catch (RuntimeException e) {
				result=CompletableFuture.failedFuture(e);
			}
			if (result==null) result=CompletableFuture.failedFuture(new NullPointerException("Resolver returned no future"));
			return result.handle((resolution,error) -> {
				if (error==null && resolution!=null) return resolution;
				Throwable cause=(error instanceof CompletionException && error.getCause()!=null)?error.getCause():error;
				return DIDResolution.error(DIDResolution.INTERNAL_ERROR,
					(cause==null)?"Resolver gave no result":String.valueOf(cause.getMessage()));
			});
		};
	}
}
