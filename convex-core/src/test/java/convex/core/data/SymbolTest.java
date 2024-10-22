package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.test.Samples;

public class SymbolTest {

	@Test
	public void testBadSymbols() {
		assertNotNull(Symbol.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH)));

		assertNull(Symbol.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH+1)));
		assertNull(Symbol.create(""));
		assertNull(Symbol.create((String)null));
	}
	
	@Test
	public void testEmbedded() {
		// max length Symbol should be embedded
		Symbol s=Symbol.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH));
		assertTrue(s.isEmbedded());
		
		doSymbolTest(s);
	}
	

	@Test 
	public void testComparable() {
		assertEquals(0,Symbols.FOO.compareTo(Symbols.FOO));
		assertEquals(-1,Symbols.BAR.compareTo(Symbols.FOO));
		assertEquals(1,Symbols.FOO.compareTo(Symbols.BAR));
	}
	
	@Test
	public void testPrint() {
		Symbol s=Symbol.create("foobar");
		assertEquals(s.getName(),RT.print(s));
		doSymbolTest(s);
	}
	
	@Test 
	public void testMaxSize() {
		Symbol max=Symbol.create(Samples.MAX_SYMBOLIC);
		assertEquals(Constants.MAX_NAME_LENGTH,max.getName().count());
		
		Symbol blown=Symbol.create(Samples.TOO_BIG_SYMBOLIC);
		assertNull(blown);
	}
	
	@Test
	public void testToString() {
		assertEquals("foo",Symbols.FOO.toString());
		doSymbolTest(Symbols.FOO);
	}

	@Test
	public void testBadFormat() {
		// should fail because this is an empty String (0 length field)
		assertThrows(BadFormatException.class, () -> Symbol.read(Blob.fromHex("3200"),0));
		
		// Should fail because reading past end of blob
		assertThrows(IndexOutOfBoundsException.class, () -> Symbol.read(Blob.fromHex("00"),0));
	}

	@Test
	public void testNormalSymbol() {
		Symbol cs = Symbol.create("count");
		assertEquals(Symbols.COUNT, cs);

		assertEquals("count", cs.getName().toString());
		assertEquals("count", cs.toString());
		assertEquals(7, cs.getEncoding().count); // tag(1) + length(1) + name(5)
		doSymbolTest(cs);
	}
	
	@Test
	public void testSymbolHash() {
		HashSet<Symbol> hs=new HashSet<>();
		hs.add(Symbols.STAR_JUICE_PRICE);
		assertTrue(hs.contains(Symbols.STAR_JUICE_PRICE));
		
	}
	
	private void doSymbolTest(Symbol s) {
		assertTrue(Symbol.validateName(s.getName()));
		
		BlobsTest.doBlobLikeTests(s);
	}

}
