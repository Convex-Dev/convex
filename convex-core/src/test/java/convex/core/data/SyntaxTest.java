package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

public class SyntaxTest {

	@Test public void testSyntaxEncodingEmptyMetaData() throws BadFormatException {
		// check that empty metadata gets encoded as nil for efficiency.
		Syntax emptyMeta=Syntax.create(RT.cvm(1L));
		Blob encoded = emptyMeta.getEncoding();
		assertEquals("88090100",encoded.toHexString());
		Syntax recovered = Format.read(encoded);
		assertEquals(emptyMeta,recovered);
		
		// should be invalid to have an empty map as encoded metadata
		assertThrows(BadFormatException.class,()->Format.read("880901820000"));
		assertThrows(BadFormatException.class,()->Format.read("8800820000"));
	}
	
	/**
	 * A Syntax wrapped in another Syntax should not be a valid encoding
	 * @throws BadFormatException 
	 */
	@Test public void testNoDoubleWrapping() throws BadFormatException {
		// A valid Syntax Object
		Syntax inner=Syntax.create(null);
		Syntax badSyntax=Syntax.createUnchecked(inner, Maps.empty());
		assertEquals(inner,badSyntax.getValue());
		assertSame(Maps.empty(),badSyntax.getMeta());
		
		// Should fail validation
		assertThrows(InvalidDataException.class,()->badSyntax.validate());
	}
	
	@Test public void testSyntaxMergingMetaData() {
		// default to empty metadata
		Syntax s1=Syntax.create(RT.cvm(1L));
		assertSame(Maps.empty(),s1.getMeta());
		
		// Should wrap once only and merge metadata
		Syntax s2=Syntax.create(s1,Maps.of(1,2,3,4));
		assertEquals(RT.cvm(1L),(CVMLong)s2.getValue());
		
		// Should wrap once only and merge new metadata, overwriting original value
		Syntax s3=Syntax.create(s2,Maps.of(3,7));
		assertEquals(RT.cvm(1L),(CVMLong)s3.getValue());
		assertEquals(Maps.of(1,2,3,7),s3.getMeta());
		
	}
}
