package convex.auth.did;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Represents a W3C Decentralized Identifier (DID) with method and id
 * 
 * A DID follows the format: did:method:method_specific_id
 * 
 * Examples:
 * - did:web:example.com
 * - did:web:example.com:8080
 * - did:convex:user.mike
 */
public class DID {

	private static final String URI_SCHEME = "did";
	private static final String DID_START = URI_SCHEME+":";
    
    private final String method;
    private final String id;
    
    /**
     * Constructs a DID with the specified components.
     * 
     * @param method The DID method (e.g., "web", "key", "peer")
     * @param id The DID identifier
     */
    public DID(String method, String id) {
        if (method == null) {
            throw new IllegalArgumentException("DID method cannot be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("DID id cannot be null");
        }
        
        this.method = method;
        this.id = id;
    }
    
    /**
     * Constructs a DID from a URI.
     * 
     * @param uri The URI to parse
     * @return A new DID instance
     * @throws IllegalArgumentException if the URI is not a valid DID
     */
    public static DID fromURI(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI cannot be null");
        }
        
        String scheme=uri.getScheme();
        if (!URI_SCHEME.equals(scheme)) {
        	throw new IllegalArgumentException("DID must start with 'did:' URI scheme");
        }
        
        // URI path contains DID method, ID and DID path
        String ssp=uri.getRawSchemeSpecificPart();
        int methodColon=ssp.indexOf(':');
        if (methodColon<0) {
        	throw new IllegalArgumentException("DID must start with 'did:<method>:<id>'");
        }
        String method=ssp.substring(0,methodColon);
        if (method.isEmpty()) throw new IllegalArgumentException("DID must have non-empty method");
        
        String id=ssp.substring(methodColon+1);
        
        // chop off query
        int queryPos=id.indexOf('?');
        if (queryPos>=0) {
        	id=id.substring(0, queryPos);
        } 
        
        // chop off path
        int slashPos=id.indexOf('/');
        if (slashPos>=0) {
        	id=id.substring(0, slashPos);
        } 
        
        id=URLDecoder.decode(id,StandardCharsets.UTF_8);
        return new DID(method, id);
    }
    
    /**
     * Constructs a DID from a string representation.
     * 
     * @param didString The DID string to parse
     * @return A new DID instance
     * @throws IllegalArgumentException if the string is not a valid DID
     */
    public static DID fromString(String didString) {
        return fromURI(URI.create(didString));
    }
    
    /**
     * Gets the DID method.
     * 
     * @return The DID method
     */
    public String getMethod() {
        return method;
    }
    
    /**
     * Gets the DID identifier.
     * 
     * @return The DID identifier
     */
    public String getID() {
        return id;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof DID other ) {
        	return method.equals(other.method) &&
               id.equals(other.id);
        } else {
        	return false;
        }
    }
    
    @Override
    public int hashCode() {
        int result = method.hashCode();
        result = 31 * result + id.hashCode();
        return result;
    }
    
    @Override
    public String toString() {
        return DID_START+method+":"+URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

	public static DID create(String method, String id) {
		return new DID(method,id);
	}

	public DID withPath(String string) {
		// TODO Auto-generated method stub
		return null;
	}
}
