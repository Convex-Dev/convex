package convex.net;

import java.net.URI;

import convex.core.data.AccountKey;

/**
 * A lattice peer as an operator names it: a peer URI and, optionally, the key it is
 * expected to prove. {@link PeerResolver#parse(String)} reads one without network access.
 *
 * @param uri {@code tcp://}, {@code tls://}, {@code did:web} or {@code did:key} URI
 * @param key expected key, or null if any key the URI allows is accepted
 */
public record PeerName(URI uri,AccountKey key) {

	public PeerName {
		if (uri==null) throw new IllegalArgumentException("A peer name needs a URI");
	}

	@Override
	public String toString() {
		return (key==null)?uri.toString():key+"@"+uri;
	}
}
