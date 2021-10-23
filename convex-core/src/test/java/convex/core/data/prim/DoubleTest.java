package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;

public class DoubleTest {

	@Test public void testNanEncoding() {
		CVMDouble nan=CVMDouble.NaN;
		
		assertEquals(Blob.fromHex("0d7ff8000000000000"),nan.getEncoding());
		
		Blob BAD_NAN=Blob.fromHex("0d7ff8000000ffffff");
		
		assertThrows(BadFormatException.class,()->Format.read(BAD_NAN));
	}
}
