package convex.did;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

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
        
    	{   // + used for space in query
    		DIDURL durl= DIDURL.create("did:foo:20?a+b");
	        assertEquals("a b",durl.getQuery());
	        doDIDURLTest("did:foo:20?a+b");
    	}
    	
    	// TODO
//    	{   // + used for space in fragment
//    		DIDURL durl= DIDURL.create("did:foo:20#a+b");
//	        assertEquals("a b",durl.getFragment());
//	        doDIDURLTest("did:foo:20#a+b");
//    	}

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

	private void doDIDTest(DID did) {
		String ds=did.toString();
		DID did2=DID.fromString(ds);
		
		assertEquals(did,did2);
		assertNotNull(did.getMethod());
		assertNotNull(did.getID());
	}
}
