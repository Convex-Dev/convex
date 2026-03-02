package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.cvm.Keywords;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.test.Samples;

public class KeywordTest {

	@Test
	public void testBadKeywords() {
		assertNotNull(Keyword.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH)));

		// null return for invalid names
		assertNull(Keyword.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH+1)));
		assertNull(Keyword.create(""));
		assertNull(Keyword.create((String)null));

		// exception for invalid names using createChecked
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked(Text.whiteSpace(Constants.MAX_NAME_LENGTH+1)));
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked(""));
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked((AString)null));
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked((String)null));
	}

	@Test
	public void testBadFormat() {
		// should fail because this is an empty String
		assertThrows(BadFormatException.class, () -> Samples.TEST_STORE.decode(Blob.fromHex("3300")));
	}
	
	@Test 
	public void testRoundTripRegression() throws BadFormatException {
		Keyword k=Keyword.create("key17");
		
		Blob enc=Blob.fromHex("33056b65793137");
		
		assertEquals(enc,k.getEncoding());
		Keyword k2=Samples.TEST_STORE.decode(enc);
		
		assertEquals(k,k2);
		assertEquals(enc,k.getEncoding());
		
		doKeywordTest(k);
	}
	
	@Test 
	public void testMaxSize() {
		Keyword max=Keyword.create(Samples.MAX_SYMBOLIC);
		assertEquals(Constants.MAX_NAME_LENGTH,max.getName().count());
		doKeywordTest(max);
		
		Keyword blown=Keyword.create(Samples.TOO_BIG_SYMBOLIC);
		assertNull(blown);
	}

	@Test
	public void testNormalKeyword() {
		Keyword k = Keyword.create("foo");
		assertEquals(Samples.FOO, k);

		assertEquals("foo", k.getName().toString());
		assertEquals(":foo", k.toString());
		assertEquals(5, k.getEncoding().count); // tag+length+3 name
		
		assertNull(RT.print(k, 3));
		assertEquals(k.print(),RT.print(k, 4));
		
		doKeywordTest(k);
	}
	
	/**
	 * Generic tests for any valid Keyword
	 * @param k Any valid keyword
	 */
	public void doKeywordTest(Keyword k) {
		AString name=k.getName();
		assertEquals(k,Keyword.create(name));
		
		// fallback to generic bloblike tests
		BlobsTest.doBlobLikeTests(k);
	}

	@Test
	public void testIntern() {
		// Test that interning a Symbol results in the same symbol for subsequent creates
		Keyword s1 = Keyword.intern("shouldBeInterned");
		Keyword s2 = Keyword.create("shouldBeInterned");
		assertSame(s1, s2);

		// Test that creating a Symbol with the same name does not intern the same symbol
		Keyword s3 = Keyword.create("shouldNotBeInterned");
		Keyword s4 = Keyword.create("shouldNotBeInterned");
		assertNotSame(s3, s4);

		// Test that the Symbols class is interning the correct symbols
		assertSame(Keywords.FOO,Keyword.create("foo"));
	}
}
