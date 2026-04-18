package convex.auth.did;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AString;

public class DIDTest {
    
    @Test
    void testBasicDIDConstruction() {
        DID did = new DID("web", "example.com");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getID());
        assertEquals("did:web:example.com", did.toString());
    }
    
    @Test
    void testDIDWithPath() {
        DID did = new DID("web", "example.com");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getID());
        assertEquals("did:web:example.com", did.toString());
    }
    
 
    
    @Test
    void testFromStringBasic() {
        DID did = DID.fromString("did:web:example.com");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getID());
    }
    
    @Test
    void testFromStringWithPath() {
        DID did = DID.fromString("did:web:example.com/path");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getID());
    }

    
    @Test
    void testFromStringWithPathAndPort() {
        DID did = DID.fromString("did:web:example.com:8080/api");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com:8080", did.getID());
    }
    
    @Test
    void testFromStringComplexPath() {
        DID did = DID.fromString("did:web:example.com:8080/api/v1/users/123");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com:8080", did.getID());
    }

    
    @Test
    void testFromStringInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("invalid:format");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("did:web");
        });
    }
    
    @Test
    void testFromStringNull() {
        assertThrows(NullPointerException.class, () -> {
            DID.fromString(null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("   ");
        });
    }
    
    @Test
    void testFromURI() throws URISyntaxException {
        URI uri = new URI("did:web:example.com/api");
        DID did = DID.fromURI(uri);
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getID());
    }
    
    @Test
    void testFromURINull() {
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromURI(null);
        });
    }
    
    @Test
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> {
            new DID(null, "example.com");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new DID(null, "example.com");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new DID("web", null);
        });
    }
    
    @Test
    void testEquals() {
        DID did1 = new DID("web", "example.com");
        DID did2 = DID.fromString("did:web:example.com/foo"); // equal up to path
        DID did2a = DID.fromString("did:web:example.com#foo"); // equal up to fragment
        DID did2b = DID.fromString("did:web:example.com?q=1"); // equal up to query
        DID did3 = new DID("web", "example.com:8080");
        DID did4 = new DID("key", "z6MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ2");
        
        assertEquals(did1, did2);
        assertEquals(did1, did2a);
        assertEquals(did1, did2b);
        assertNotEquals(did1, did3);
        assertNotEquals(did1, did4);
        assertNotEquals(did1, "not a DID");
        assertNotEquals(did1, null);
    }
    
    @Test
    void testHashCode() {
        DID did1 = new DID("web", "example.com");
        DID did2 = new DID("web", "example.com");
        
        assertEquals(did1.hashCode(), did2.hashCode());
    }
    
    @Test
    void testEncodings() {
    	{	// Unicode character as UTF-8 bytes
	        DID did1 = new DID("foo","ŭ");
	        assertEquals("did:foo:%C5%AD", did1.toString());
	        doDIDTest(did1);
        }
        
    	{   // + used for space in query (URLDecoder interprets + as space)
    		DIDURL durl= DIDURL.create("did:foo:20?a+b");
	        assertEquals("a b",durl.getQuery());
	        // Round-trip uses RFC 3986 encoding: space becomes %20 not +
	        assertEquals("did:foo:20?a%20b", durl.toString());
    	}

    	{   // + is literal in fragment (RFC 3986 — only query has + as space in form encoding)
    		DIDURL durl= DIDURL.create("did:foo:20#a+b");
	        assertEquals("a+b",durl.getFragment());
	        assertEquals("did:foo:20#a+b", durl.toString());
    	}

    }
    
    @Test
    void testDifferentMethods() {
        DID webDid = new DID("web", "example.com");
        DID keyDid = new DID("key", "example.com");
        
        assertNotEquals(webDid, keyDid);
        assertEquals("did:web:example.com", webDid.toString());
        assertEquals("did:key:example.com", keyDid.toString());
    }
    
    @Test
    public void testDIDURL() {
    	String DID="did:web:example.com/foo";
    	DIDURL durl=DIDURL.create(DID);
    	DID did=durl.getDID();
    	
    	assertEquals("did:web:example.com",did.toString());
    	
    	assertEquals(DID,durl.toString());
    	assertEquals(DID,durl.toAString().toString());
    }
    
    @Test public void testExamples() {
    	doDIDURLTest("did:a:b/c/d?e#f");
    	doDIDURLTest("did:key:zzzzz?foo");
    	doDIDURLTest("did:key:zzzzz#foo%C5%AD");
    	doDIDURLTest("did:web:venue-test.covia.ai");
    }
    
    public void doDIDURLTest(String didUrl) {
    	DIDURL durl=DIDURL.create(didUrl);
    	
    	assertEquals(didUrl,durl.toString());
    	
    	doDIDTest(durl.getDID());
    }

    @Test
    void testForKeyCompoundDIDURL() {
    	AKeyPair kp = AKeyPair.generate();
    	AString did = DID.forKey(kp.getAccountKey());

    	// Build a full DID URL with path, query (with = and &), and fragment
    	String full = did.toString() + "/some/path?service=files&version=2#key-1";
    	DIDURL durl = DIDURL.create(full);

    	assertEquals("key", durl.getDID().getMethod());
    	assertEquals("/some/path", durl.getPath());
    	assertEquals("service=files&version=2", durl.getQuery());
    	assertEquals("key-1", durl.getFragment());

    	// DID base should match the did:key we generated
    	assertEquals(did.toString(), durl.getDID().toString());

    	// Round-trip: string representation should match input
    	assertEquals(full, durl.toString());

    	// Verify withPath/withQuery/withFragment produce new DIDURLs
    	DIDURL modified = durl.withFragment("alt-frag");
    	assertEquals("alt-frag", modified.getFragment());
    	assertEquals(durl.getPath(), modified.getPath());
    }

    @Test
    void testForKey() {
    	AKeyPair kp = AKeyPair.generate();
    	AString did = DID.forKey(kp.getAccountKey());

    	assertNotNull(did);
    	assertTrue(did.toString().startsWith("did:key:z"));

    	// Same key should produce same DID
    	assertEquals(did, DID.forKey(kp.getAccountKey()));

    	// Different key should produce different DID
    	AKeyPair kp2 = AKeyPair.generate();
    	assertNotEquals(did, DID.forKey(kp2.getAccountKey()));

    	// Should be parseable as a DID
    	DID parsed = DID.fromString(did.toString());
    	assertEquals("key", parsed.getMethod());
    }

	private void doDIDTest(DID did) {
		String ds=did.toString();
		DID did2=DID.fromString(ds);
		
		assertEquals(did,did2);
		assertNotNull(did.getMethod());
		assertNotNull(did.getID());
	}
}
