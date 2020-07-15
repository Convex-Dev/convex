package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

public class SyntaxTest {

	@Test public void testSyntaxEncodingEmptyMetaData() throws BadFormatException {
		// check that empty metadata gets encoded as nil for efficiency.
		Syntax emptyMeta=Syntax.create(1L);
		Blob encoded = emptyMeta.getEncoding();
		assertEquals("34090100",encoded.toHexString());
		Syntax recovered = Format.read(encoded);
		assertEquals(emptyMeta,recovered);
		
		// should be invalid to have an empty map as encoded metadata
		assertThrows(BadFormatException.class,()->Format.read("340901820000"));
		assertThrows(BadFormatException.class,()->Format.read("3400820000"));
	}
	
	/**
	 * A Syntax wrapped in another Syntax should not be a valid encoding
	 */
	@Test public void testNoDoubleWrapping() throws BadFormatException {
		// A valid Syntax Object
		Syntax inner=Syntax.create(null);
		Ref<?> innerRef=Ref.createPersisted(inner);
		
		// This is an invalid Syntax object.
		// However, it should read OK, because it looks like a valid Syntax object containing a Ref
		Syntax s=Format.read("3420"+innerRef.getHash().toHexString()+"00");
		assertEquals(inner,s.getValue());
		assertSame(Maps.empty(),s.getMeta());
		
		// Should fail validation
		assertThrows(InvalidDataException.class,()->s.validate());
	}
	
	@Test public void testSyntaxMergingMetaData() {
		// default to empty metadata
		Syntax s1=Syntax.create(1L);
		assertSame(Maps.empty(),s1.getMeta());
		
		// Should wrap once only and merge metadata
		Syntax s2=Syntax.create(s1,Maps.of(1,2,3,4));
		assertEquals(1L,(long)s2.getValue());
		
		// Should wrap once only and merge new metadata, overwriting original value
		Syntax s3=Syntax.create(s2,Maps.of(3,7));
		assertEquals(1L,(long)s3.getValue());
		assertEquals(Maps.of(1,2,3,7),s3.getMeta());
		
	}
}
