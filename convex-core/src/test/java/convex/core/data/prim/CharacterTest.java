package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.ObjectsTest;
import convex.core.data.Strings;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;

public class CharacterTest {
	@Test 
	public void testZeroEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create(0);
		doValidCharTests(a);
		assertEquals("3c00",a.getEncoding().toHexString());
	}
	
	@Test 
	public void testBasicEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create('z');
		doValidCharTests(a);
		assertEquals("3c7a",a.getEncoding().toHexString());
	}
	
	@Test 
	public void testExtendedEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create('\u1234');
		doValidCharTests(a);
		assertEquals("3d1234",a.getEncoding().toHexString());
	}
	
	@Test 
	public void testMaxValue() throws BadFormatException {
		CVMChar a=CVMChar.create(CVMChar.MAX_CODEPOINT);
		doValidCharTests(a);
		assertEquals("3e10ffff",a.getEncoding().toHexString());
		assertEquals("f48fbfbf",a.toUTFBlob().toHexString());
	}
	
	@Test 
	public void testBadUnicode() throws BadFormatException {
		assertNull(CVMChar.create(0x12345678));          // Out of Unicode range, too big
		assertNull(CVMChar.create(-1));                  // Out of Unicode range, negative
		assertNull(CVMChar.create(CVMChar.MAX_CODEPOINT+1)); // Out of Unicode range by one
	}
	
	@Test 
	public void testBadFormats() {
		// Leading zero in encoding of 2-byte character
		assertThrows(BadFormatException.class,()->Format.read("3d0012"));
		
		// Leading zero in encoding of 4-byte character
		assertThrows(BadFormatException.class,()->Format.read("3f00123456"));

		// Out of Unicode range
		assertThrows(BadFormatException.class,()->Format.read("3f12345678"));

	}
	
	@Test
	public void testSamples() {
		
	}
	
	public void doValidCharTests(CVMChar a) throws BadFormatException {
		assertNotNull(a);
		Blob b=a.getEncoding();
		assertEquals(a,Format.read(b));
		
		byte[] bs=a.toUTFBytes();
		assertEquals(a.toString(),new String(bs,StandardCharsets.UTF_8));
		assertEquals(bs.length,CVMChar.utfLength(a.getCodePoint()));
		assertEquals(a.getCodePoint(),a.longValue());
		
		assertEquals(0,a.compareTo(a));
		
		assertEquals(a.toCVMString(10),Strings.create(new BlobBuilder().append(a).toBlob()));
		
		ObjectsTest.doAnyValueTests(a);
	}
	
	@Test public void testCompare() {
		assertTrue(CVMChar.MAX_VALUE.compareTo(CVMChar.ZERO)>0);
	}


}
