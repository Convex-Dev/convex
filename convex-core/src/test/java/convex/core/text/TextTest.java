package convex.core.text;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.text.ParseException;

import org.junit.Test;

import convex.core.cvm.Address;

public class TextTest {

	@Test
	public void testWhiteSpace() {
		checkWhiteSpace(0);
		checkWhiteSpace(10);
		checkWhiteSpace(32);
		checkWhiteSpace(33);
		checkWhiteSpace(95);
		checkWhiteSpace(96);
		checkWhiteSpace(97);
		checkWhiteSpace(100);
	}
	
	@Test
	public void testUnescapeJava() {
		assertEquals("foo",Text.unescapeJava("foo"));
		
		assertEquals("\\",Text.unescapeJava("\\"));
		
		assertEquals("zzAzzB",Text.unescapeJava("zz\\u0041zz\\u0042"));
		assertNull(Text.unescapeJava("\\u"));
		assertNull(Text.unescapeJava("\\u0x0x")); // not valid unicode
		assertNull(Text.unescapeJava("\\u012")); // not valid unicode (only 3 chars)
		
		// octal escapes
		assertEquals("a%b",Text.unescapeJava("a\\45b")); 
		assertEquals("\19",Text.unescapeJava("\\19")); 
		assertEquals("\0\0",Text.unescapeJava("\\0\\0")); 
		assertEquals("\1\11\111",Text.unescapeJava("\\1\\11\\111")); 
		assertEquals("!0",Text.unescapeJava("\\410")); // Yeah, JLS has max octal value of 377. Don't ask why...
	}

	private void checkWhiteSpace(int len) {
		String s = Text.whiteSpace(len);
		assertEquals(len, s.length());
		assertEquals("", s.trim());
	}
	
	@Test public void testFriendlyNumbers() {
		assertEquals("10%",Text.toPercentString(0.1));
		assertEquals("0.1%",Text.toPercentString(0.001));
	}
	
	@Test public void testFriendlyDecimals() {
		assertEquals("0.1",Text.toFriendlyDecimal(0.1));
		assertEquals("1,000.51",Text.toFriendlyDecimal(1000.51));
	}
	
	@Test public void testAddressFormat() throws ParseException {
		AddressFormat af=AddressFormat.INSTANCE;
		
		assertEquals(Address.create(1234),af.parseObject("#1234"));
		assertEquals(Address.create(0),af.parseObject("0"));
		assertEquals(Address.create(Long.MAX_VALUE),af.parseObject("#"+Long.MAX_VALUE));
		
		assertEquals("#789",af.format(Address.create(789)));
		assertEquals("#0",af.format(Address.ZERO));
		assertEquals("#9223372036854775807",af.format(Address.create(Long.MAX_VALUE)));
	}
}
