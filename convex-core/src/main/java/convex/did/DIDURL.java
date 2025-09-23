package convex.did;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Represents a W3C Decentralized Identifier URL (DID URL).
 * 
 * <p>A DID URL extends a basic DID with additional path, query, and fragment components,
 * following the format: {@code did:method:method_specific_id/path/to/something?q=blah#abc}</p>
 * 
 * <p>This class provides immutable access to the components of a DID URL and supports
 * parsing from string representations and URI objects.</p>
 * 
 * @see DID
 * @see <a href="https://www.w3.org/TR/did-core/#did-url-syntax">W3C DID URL Syntax</a>
 */
public class DIDURL {


    /** The base DID component of this DID URL */
    final DID did;
    
    /** The path component of this DID URL, or null if not present */
	final String path;
	
	/** The query component of this DID URL, or null if not present */
	final String query;
	
	/** The fragment component of this DID URL, or null if not present */
	final String fragment;
	
	/** Cached AString representation of this DID URL */
	private volatile AString aStringCache=null;

	/**
	 * Constructs a new DIDURL with the specified components.
	 * 
	 * @param did the base DID component (must not be null)
	 * @param path the path component (may be null)
	 * @param query the query component (may be null)
	 * @param fragment the fragment component (may be null)
	 * @throws IllegalArgumentException if did is null
	 */
	public DIDURL(DID did, String path, String query, String fragment) {
    	this.did=did;
    	this.path=path;
    	this.query=query;
    	this.fragment=fragment;
    }
	
	public DIDURL(DID did) {
		this(did,null,null,null);
	}

	/**
	 * Creates a DIDURL from a string representation.
	 * 
	 * @param didURL the string representation of the DID URL
	 * @return a new DIDURL instance parsed from the string
	 * @throws IllegalArgumentException if the string is not a valid URI or DID URL
	 */
	public static DIDURL create(String didURL) {
		return create(URI.create(didURL));
	}
	

	public static DIDURL create(DID did) {
		return new DIDURL(did);
	}

	/**
	 * Creates a DIDURL from a URI object.
	 * 
	 * @param uri the URI to parse
	 * @return a new DIDURL instance parsed from the URI
	 * @throws IllegalArgumentException if the URI is not a valid DID URL
	 */
	private static DIDURL create(URI uri) {
		DID did=DID.fromURI(uri);
		String path=null; // needs to come from ssp
		String query=null; // needs to come from ssp
		String fragment=uri.getFragment();
		
		String ssp=uri.getRawSchemeSpecificPart();
		
		int qpos=ssp.indexOf('?');
		if (qpos>=0) {
			query=URLDecoder.decode(ssp.substring(qpos+1),StandardCharsets.UTF_8);
			ssp=ssp.substring(0, qpos);
		}

		int ppos=ssp.indexOf('/');
		if (ppos>=0) {
			path=ssp.substring(ppos);
			ssp=ssp.substring(0, ppos);
		}
		
		return new DIDURL(did,path,query,fragment);
	}
	
	public DIDURL withPath(String newPath) {
		return new DIDURL(did,newPath,query,fragment);
	}
	
	public DIDURL withQuery(String newQuery) {
		return new DIDURL(did,path,newQuery,fragment);
	}
	
	public DIDURL withFragment(String newFragment) {
		return new DIDURL(did,path,query,newFragment);
	}
	
	public DIDURL withDID(DID newDID) {
		return new DIDURL(newDID,path,query,fragment);
	}

	/**
	 * Returns the base DID component of this DID URL.
	 * 
	 * @return the DID component (never null)
	 */
	public DID getDID() {
		return did;
	}

	/**
	 * Returns the path component of this DID URL.
	 * 
	 * @return the path component, or null if not present
	 */
	public String getPath() {
		return path;
	}

	/**
	 * Returns the query component of this DID URL.
	 * 
	 * @return the query component, or null if not present
	 */
	public String getQuery() {
		return query;
	}

	/**
	 * Returns the fragment component of this DID URL.
	 * 
	 * @return the fragment component, or null if not present
	 */
	public String getFragment() {
		return fragment;
	}

	/**
	 * Compares this DIDURL with another object for equality.
	 * 
	 * @param obj the object to compare with
	 * @return true if the objects are equal, false otherwise
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null || getClass() != obj.getClass()) return false;
		DIDURL didurl = (DIDURL) obj;
		return Objects.equals(did, didurl.did) &&
			   Objects.equals(path, didurl.path) &&
			   Objects.equals(query, didurl.query) &&
			   Objects.equals(fragment, didurl.fragment);
	}

	/**
	 * Returns a hash code for this DIDURL.
	 * 
	 * @return a hash code value for this object
	 */
	@Override
	public int hashCode() {
		return Objects.hash(did, path, query, fragment);
	}

	/**
	 * Returns a string representation of this DID URL.
	 * 
	 * <p>The string format reconstructs the original DID URL by concatenating
	 * the DID with any present path, query, and fragment components.</p>
	 * 
	 * @return a string representation of this DID URL
	 */
	@Override
	public String toString() {

		String result=did.toString();
		if (path!=null) { 
			result+=path;
		}
		if (query!=null) {
			result+="?"+URLEncoder.encode(query, StandardCharsets.UTF_8);
		}
		if (fragment!=null) {
			result+="#"+URLEncoder.encode(fragment, StandardCharsets.UTF_8);;
		}
		return result;
	}

	/**
	 * Returns an AString representation of this DID URL.
	 * 
	 * <p>The AString is cached on first creation for performance. The cached
	 * instance is thread-safe and will be reused for subsequent calls.</p>
	 * 
	 * @return an AString representation of this DID URL
	 */
	public AString toAString() {
		AString result = aStringCache;
		aStringCache = result = Strings.create(toString());
		return result;
	}


}
