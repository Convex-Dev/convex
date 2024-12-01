package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Syntax;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.Reader;

public class SyntaxTest {

	@Test public void testSyntaxEncodingEmptyMetaData() throws BadFormatException {
		// check that empty metadata gets encoded as nil for efficiency.
		Syntax emptyMeta=Syntax.create(RT.cvm(1L));
		Blob encoded = emptyMeta.getEncoding();
		assertEquals("88110100",encoded.toHexString());
		Syntax recovered = Format.read(encoded);
		assertEquals(emptyMeta,recovered);
		
		// should be invalid to have an empty map as encoded metadata
		assertThrows(BadFormatException.class,()->Format.read("881101820000"));
		assertThrows(BadFormatException.class,()->Format.read("8800820000"));
		assertThrows(BadFormatException.class,()->Format.read("88008200"));
	}
	
	@Test public void testSyntaxExamples() {
		Syntax s1= Syntax.create(Address.create(32));
		
		doSyntaxTest(s1);
	}
	
	private void doSyntaxTest(Syntax s) {
		ObjectsTest.doAnyValueTests(s);
	}

	/**
	 * A Syntax wrapped in another Syntax should not be a valid encoding
	 * @throws BadFormatException  On format error
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
	
	@Test public void testSyntaxPrintRegression() {
		String s="^{} 0xa89e59cc8ab9fc6a13785a37938c85b306b24663415effc01063a6e25ef52ebcd3647d3a77e0a33908a372146fdccab6";
		int n=s.length();
		Syntax a=Reader.read(s);
		assertNotNull(a);
		
		BlobBuilder bb=new BlobBuilder();
		assertFalse(a.print(bb,n-1));
		
		bb.clear();
		assertTrue(a.print(bb,n));
		assertEquals(n,bb.count());
	}
}
