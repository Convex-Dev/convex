package convex.net;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import convex.auth.did.DID;
import convex.auth.did.DIDDocuments;
import convex.auth.did.DIDResolution;
import convex.auth.did.DIDResolver;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/**
 * Resolves the names operators give lattice peers into {@link PeerTarget}s: the keys
 * a peer may prove and the endpoints to dial it at. Every place that names a peer
 * accepts the same names, handled by scheme:
 *
 * <ul>
 * <li>{@code tcp://host:port}, {@code tls://host:port} or {@code host:port}: the URI is
 * the endpoint, and the peer is admitted under the key it proves.</li>
 * <li>{@code did:web} and {@code did:key}: the DID is resolved through the shared
 * {@link DIDResolver}. The peer must prove a key the document lists under
 * {@code authentication}, and is dialled at the endpoints of the document's
 * {@value #LATTICE_SERVICE_TYPE} services or, failing those, at the transports this
 * node's records hold for its key.</li>
 * </ul>
 *
 * <p>An expected key beside the name narrows the keys the peer may prove to that one.
 * Names are checked by {@link #parse} without network access; anything else fails as
 * unsupported.</p>
 */
public class PeerResolver {

	/** DID document service type whose endpoints are a lattice node's transports. */
	public static final String LATTICE_SERVICE_TYPE="ConvexLatticeNode";

	private static final AString LATTICE_SERVICE=Strings.intern(LATTICE_SERVICE_TYPE);
	private static final String SUPPORTED="name a peer by tcp://host:port, tls://host:port, host:port, did:web or did:key";

	private final DIDResolver dids;
	private final Function<AccountKey,AVector<AString>> records;

	/**
	 * Creates a resolver.
	 *
	 * @param dids shared DID resolver
	 * @param records transports this node's records hold for a key, or null if it keeps none
	 */
	public PeerResolver(DIDResolver dids,Function<AccountKey,AVector<AString>> records) {
		if (dids==null) throw new IllegalArgumentException("A peer resolver needs a DID resolver");
		this.dids=dids;
		this.records=records;
	}

	/**
	 * Creates a resolver for {@code did:key} and {@code did:web}, fetching documents with
	 * the JVM's TLS defaults, for a node that keeps no records.
	 *
	 * @return peer resolver
	 */
	public static PeerResolver create() {
		return new PeerResolver(defaultDIDResolver(),null);
	}

	/** @return the DID resolver peers use by default: {@code did:key} and {@code did:web} */
	public static DIDResolver defaultDIDResolver() {
		return DIDResolver.byMethod(Map.of("key",DIDResolver.KEY,"web",new DIDWebResolver()));
	}

	/**
	 * Returns a resolver like this one that locates peers by key in the given records.
	 *
	 * @param records transports this node's records hold for a key
	 * @return peer resolver
	 */
	public PeerResolver withRecords(Function<AccountKey,AVector<AString>> records) {
		return new PeerResolver(dids,records);
	}

	/**
	 * Reads a peer name as written on a command line: a peer URI, or {@code <key>@<uri>}
	 * with an expected key in hex or as a {@code did:key}.
	 *
	 * @param text peer name
	 * @return the parsed name
	 * @throws IllegalArgumentException if the name is malformed or unsupported
	 */
	public static PeerName parse(String text) {
		if (text==null || text.isBlank()) throw new IllegalArgumentException("Peer name required");
		String trimmed=text.trim();
		int at=trimmed.indexOf('@');
		if (at<0) return parse(trimmed,null);
		if (at==0 || at==trimmed.length()-1) {
			throw new IllegalArgumentException("Invalid peer "+trimmed+": expected <key>@<uri>");
		}
		return parse(trimmed.substring(at+1),trimmed.substring(0,at));
	}

	/**
	 * Reads a peer URI with an optional expected key, as configuration gives them.
	 *
	 * @param uri peer URI
	 * @param key expected key in hex or as a {@code did:key}, or null
	 * @return the parsed name
	 * @throws IllegalArgumentException if the name is malformed or unsupported
	 */
	public static PeerName parse(String uri,String key) {
		AccountKey expected=null;
		if (key!=null) {
			String k=key.trim();
			expected=k.startsWith("did:")?DID.keyFromDID(Strings.create(k)):AccountKey.parse(k);
			if (expected==null) {
				throw new IllegalArgumentException("Invalid peer key "+k+": expected hex or a did:key");
			}
		}
		if (uri==null || uri.isBlank()) throw new IllegalArgumentException("Peer URI required");
		String text=uri.trim();
		if (text.regionMatches(true,0,"did:",0,4)) return new PeerName(parseDID(text),expected);
		try {
			return new PeerName(Transports.peerEndpoint(text),expected);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(e.getMessage()+" ("+SUPPORTED+")",e);
		}
	}

	/** Checks a DID names a peer of a supported method, without network access. */
	private static URI parseDID(String text) {
		DID did;
		try {
			if (text.indexOf('/')>=0 || text.indexOf('?')>=0 || text.indexOf('#')>=0) {
				throw new IllegalArgumentException("a peer DID has no path, query or fragment");
			}
			did=DID.fromString(text);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid peer DID "+text+": "+e.getMessage(),e);
		}
		String method=did.getMethod().toLowerCase(Locale.ROOT);
		AString canonical=Strings.create("did:"+method+":"+did.getID());
		boolean valid=switch (method) {
			case "key" -> DID.keyFromDID(canonical)!=null;
			case "web" -> DIDWebResolver.documentURL(canonical)!=null;
			default -> throw new IllegalArgumentException("Unsupported peer DID method did:"+method+" ("+SUPPORTED+")");
		};
		if (!valid) throw new IllegalArgumentException("Invalid peer DID "+text);
		return URI.create(canonical.toString());
	}

	/**
	 * Resolves a peer name. A DID is resolved afresh on every call, so who the peer is
	 * and where it is are checked again each time.
	 *
	 * @param name parsed peer name
	 * @return future completing with the target, or failing with a
	 *         {@link PeerConnectionException} saying why it could not be resolved
	 */
	public CompletableFuture<PeerTarget> resolve(PeerName name) {
		AString label=Strings.create(name.toString());
		URI uri=name.uri();
		AVector<AccountKey> expected=(name.key()==null)?Vectors.empty():Vectors.of(name.key());
		if (!"did".equalsIgnoreCase(uri.getScheme())) {
			return CompletableFuture.completedFuture(
				new PeerTarget(label,expected,Vectors.of(Strings.create(uri.toString()))));
		}
		AString did=Strings.create(uri.toString());
		CompletableFuture<DIDResolution> resolution;
		try {
			resolution=dids.resolve(did);
		} catch (RuntimeException e) {
			// A DID resolver reports what it cannot resolve in its result: this is a fault
			resolution=CompletableFuture.failedFuture(e);
		}
		return resolution.thenCompose(r -> {
			try {
				return CompletableFuture.completedFuture(target(label,did,name.key(),r));
			} catch (PeerConnectionException e) {
				return CompletableFuture.failedFuture(e);
			}
		});
	}

	/** Reads a resolved DID document as a peer target. */
	private PeerTarget target(AString label,AString did,AccountKey expected,DIDResolution resolution)
			throws PeerConnectionException {
		if (resolution.getErrorType()!=null) {
			throw new PeerConnectionException("Cannot resolve peer "+did+": "+resolution);
		}
		AMap<AString,ACell> document=resolution.getDocument();
		if (resolution.isDeactivated() || document==null) {
			throw new PeerConnectionException("Peer "+did+" is deactivated");
		}
		String problem=DIDDocuments.problem(document);
		if (problem!=null) throw new PeerConnectionException("Invalid document for peer "+did+": "+problem);
		AVector<AccountKey> keys=DIDDocuments.authenticationKeys(document);
		if (keys.isEmpty()) throw new PeerConnectionException("Peer "+did+" lists no Ed25519 key for authentication");
		if (expected!=null) {
			if (!keys.contains(expected)) throw new PeerConnectionException("Peer "+did+" does not list key "+expected);
			keys=Vectors.of(expected);
		}
		AVector<AString> endpoints=Vectors.empty();
		AVector<AString> advertised=DIDDocuments.serviceEndpoints(document,LATTICE_SERVICE);
		for (long i=0; i<advertised.count(); i++) {
			AString endpoint=advertised.get(i);
			if (isDialable(endpoint)) endpoints=endpoints.conj(endpoint);
		}
		if (endpoints.isEmpty()) endpoints=located(keys);
		if (endpoints.isEmpty()) throw new PeerConnectionException("No address known for peer "+did);
		return new PeerTarget(label,keys,endpoints);
	}

	/** Transports this node's records hold for any of the keys. */
	private AVector<AString> located(AVector<AccountKey> keys) {
		AVector<AString> endpoints=Vectors.empty();
		if (records==null) return endpoints;
		for (long i=0; i<keys.count(); i++) {
			AVector<AString> found=records.apply(keys.get(i));
			if (found==null) continue;
			for (long j=0; j<found.count(); j++) {
				AString endpoint=found.get(j);
				if (isDialable(endpoint) && !endpoints.contains(endpoint)) endpoints=endpoints.conj(endpoint);
			}
		}
		return endpoints;
	}

	/** Whether this node has a transport for an endpoint. */
	private static boolean isDialable(AString endpoint) {
		if (endpoint==null) return false;
		try {
			URI uri=URI.create(endpoint.toString());
			if (uri.getScheme()==null) return false;
			Transports.forEndpoint(uri);
			return uri.getHost()!=null;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
