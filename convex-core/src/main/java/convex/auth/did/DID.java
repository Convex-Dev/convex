package convex.auth.did;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import convex.core.crypto.util.Multikey;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Strings;

/**
 * Represents a W3C Decentralized Identifier (DID) with method and id
 * 
 * A DID follows the format: did:method:method_specific_id
 * 
 * Examples:
 * - did:web:example.com
 * - did:web:example.com%3A8080 (non-default port, percent-encoded per did:web)
 * - did:convex:13
 * - did:convex:id.foo
 */
public class DID {

	private static final String URI_SCHEME = "did";
	private static final String DID_START = URI_SCHEME+":";
	private static final String DID_KEY_START = "did:key:";
	private static final AString DID_KEY_PREFIX = Strings.intern(DID_KEY_START);
    
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
        return DID_START+method+":"+encodeID(id);
    }

    /**
     * Encodes a method-specific id for string representation. Legal idchars
     * (ALPHA / DIGIT / "." / "-" / "_") and the ":" segment separator are
     * preserved per the W3C DID ABNF; all other characters are percent-encoded
     * as UTF-8. Note URLEncoder is unsuitable here: it form-encodes (space as
     * "+") and escapes the structural ":" separator.
     */
    private static String encodeID(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ':') {
                sb.append(c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return sb.toString();
    }

	public static DID create(String method, String id) {
		return new DID(method,id);
	}

	/**
	 * Creates a {@code did:key:<multikey>} AString for an Ed25519 public key.
	 *
	 * @param publicKey The Ed25519 public key
	 * @return DID string as AString (e.g. {@code did:key:z6Mk...})
	 */
	public static AString forKey(AccountKey publicKey) {
		return DID_KEY_PREFIX.append(Multikey.encodePublicKey(publicKey));
	}

	/**
	 * Extracts the Ed25519 public key from a {@code did:key:<multikey>} string.
	 *
	 * @param did The DID string, or null
	 * @return The public key, or null if the DID is not a well-formed did:key
	 */
	public static AccountKey keyFromDID(AString did) {
		if (did == null) return null;
		String s = did.toString();
		if (!s.startsWith(DID_KEY_START)) return null;
		try {
			return Multikey.decodePublicKey(s.substring(DID_KEY_START.length()));
		} catch (Exception e) {
			return null;
		}
	}

	/** Returns true iff this is the canonical string form of a base DID. */
	public static boolean isCanonicalBase(AString did) {
		if (did==null) return false;
		String text=did.toString();
		if (!text.startsWith(DID_START)) return false;
		int methodEnd=text.indexOf(':',DID_START.length());
		if (methodEnd==DID_START.length() || methodEnd<0) return false;
		for (int i=DID_START.length(); i<methodEnd; i++) {
			char c=text.charAt(i);
			if (!((c>='a'&&c<='z')||(c>='0'&&c<='9'))) return false;
		}
		int segmentLength=0;
		for (int i=methodEnd+1; i<text.length(); i++) {
			char c=text.charAt(i);
			if (c==':') {
				segmentLength=0;
				continue;
			}
			if (c=='%') {
				if (i+2>=text.length() || Character.digit(text.charAt(i+1),16)<0
						|| Character.digit(text.charAt(i+2),16)<0) return false;
				i+=2;
				segmentLength++;
				continue;
			}
			if (!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')
					|| c=='.'||c=='-'||c=='_')) return false;
			segmentLength++;
		}
		return segmentLength>0;
	}
}
