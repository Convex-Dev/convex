package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.Text;
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
		assertThrows(BadFormatException.class, () -> Keyword.read(Blob.fromHex("00").toByteBuffer()));
	}
	
	@Test 
	public void testRoundTripRegression() throws BadFormatException {
		Keyword k=Keyword.create("key17");
		
		Blob enc=Blob.fromHex("33056b65793137");
		
		assertEquals(enc,k.getEncoding());
		
		ByteBuffer bb=enc.getByteBuffer();
		assertEquals(Tag.KEYWORD,bb.get());
		Keyword k2=Keyword.read(bb);
		
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
		assertEquals(5, k.getEncoding().length); // tag+length+3 name
		
		assertNull(RT.print(k, 3));
		assertEquals(k.print(),RT.print(k, 4));
		
		doKeywordTest(k);
	}
	
	/**
	 * Generic tests for any valid Keyword
	 * @param k Any valid keyword
	 */
	public void doKeywordTest(Keyword k) {
		assertEquals(k,Keyword.create(k.getName()));
		
		ObjectsTest.doAnyValueTests(k);
	}
}
