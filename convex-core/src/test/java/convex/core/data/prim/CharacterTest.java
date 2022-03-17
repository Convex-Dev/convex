package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;

public class CharacterTest {
	@Test 
	public void testZeroEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create(0);
		Blob b=a.getEncoding();
		assertEquals("3c00",b.toHexString());
		assertEquals(a,Format.read(b));
	}
	
	@Test 
	public void testBasicEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create('z');
		Blob b=a.getEncoding();
		assertEquals("3c7a",b.toHexString());
		assertEquals(a,Format.read(b));
	}
	
	@Test 
	public void testExtendedEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create('\u1234');
		Blob b=a.getEncoding();
		assertEquals("3d1234",b.toHexString());
		assertEquals(a,Format.read(b));
	}
	
	@Test 
	public void testBigEncoding() throws BadFormatException {
		CVMChar a=CVMChar.create(0x12345678);
		Blob b=a.getEncoding();
		assertEquals("3f12345678",b.toHexString());
		assertEquals(a,Format.read(b));
	}
	
	@Test 
	public void testBadFormats() {
		// Leading zero in encoding of 2-byte character
		assertThrows(BadFormatException.class,()->Format.read("3d0012"));
		
		// Leading zero in encoding of 4-byte character
		assertThrows(BadFormatException.class,()->Format.read("3f00123456"));

	}


}
