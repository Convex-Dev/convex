package convex.auth.did;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import convex.core.crypto.util.Multikey;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.util.Utils;

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
 *
 * The method-specific id is held as written in the DID string, normalised per
 * RFC 3986 within the DID syntax: percent-encoded idchars are decoded, other
 * percent-encodings keep upper-case hex. An encoded reserved character differs
 * from the raw one, so did:web:example.com%3A8080 (a port) is not
 * did:web:example.com:8080 (a path). Methods that give an encoding meaning
 * decode it when resolving.
 */
public class DID {

	private static final String URI_SCHEME = "did";
	private static final String DID_START = URI_SCHEME+":";
	private static final String DID_KEY_START = "did:key:";
	private static final AString DID_KEY_PREFIX = Strings.intern(DID_KEY_START);
	private static final String HEX_DIGITS = "0123456789ABCDEF";

    private final String method;
    private final String id;

    /**
     * Constructs a DID with the specified components.
     *
     * @param method The DID method (e.g., "web", "key", "peer")
     * @param id The method-specific id as written in a DID string, where "%"
     *        starts a percent-encoding; other characters that may not appear
     *        raw are percent-encoded as UTF-8
     */
    public DID(String method, String id) {
        if (method == null) {
            throw new IllegalArgumentException("DID method cannot be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("DID id cannot be null");
        }
        String normalised = normaliseID(id);
        if (normalised == null) {
            throw new IllegalArgumentException("DID id is not well-formed: " + id);
        }

        this.method = method;
        this.id = normalised;
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
     * Gets the method-specific id in its normalised written form, with
     * percent-encodings in place (e.g. "example.com%3A8080").
     *
     * @return The method-specific id
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
        return DID_START+method+":"+id;
    }

    /**
     * Normalises a written method-specific id. A percent-encoded idchar
     * (ALPHA / DIGIT / "." / "-" / "_") is decoded, as RFC 3986 decodes
     * unreserved characters; "~" is unreserved but not an idchar, so it stays
     * encoded. Other percent-encodings keep their meaning, with upper-case hex.
     * Raw idchars and the ":" separator are kept; any other character is
     * percent-encoded as UTF-8. Note URLDecoder is unsuitable here: it decodes
     * reserved characters and form-decodes "+" as space.
     *
     * @return The normalised id, or null if a percent-encoding is malformed or
     *         the id is not valid UTF-16
     */
    private static String normaliseID(String s) {
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= n) return null;
                int hi = Utils.hexVal(s.charAt(i + 1));
                int lo = Utils.hexVal(s.charAt(i + 2));
                if ((hi < 0) || (lo < 0)) return null;
                int b = (hi << 4) | lo;
                if (isIDChar((char) b)) {
                    sb.append((char) b);
                } else {
                    appendEncoded(sb, b);
                }
                i += 2;
            } else if (isIDChar(c) || (c == ':')) {
                sb.append(c);
            } else {
                int cp = s.codePointAt(i);
                if (Character.getType(cp) == Character.SURROGATE) return null;
                for (byte b : Character.toString(cp).getBytes(StandardCharsets.UTF_8)) {
                    appendEncoded(sb, b & 0xFF);
                }
                i += Character.charCount(cp) - 1;
            }
        }
        return sb.toString();
    }

    private static boolean isIDChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '-' || c == '_';
    }

    private static void appendEncoded(StringBuilder sb, int b) {
        sb.append('%');
        sb.append(HEX_DIGITS.charAt(b >> 4));
        sb.append(HEX_DIGITS.charAt(b & 0xF));
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

	/**
	 * Returns true iff this is the canonical string form of a base DID: a
	 * lower-case method, no path, query or fragment, a non-empty final id
	 * segment, and an id already normalised as {@link #getID()} returns it.
	 */
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
		String id=text.substring(methodEnd+1);
		if (id.isEmpty() || id.endsWith(":")) return false;
		return id.equals(normaliseID(id));
	}
}
