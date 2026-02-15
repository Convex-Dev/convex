package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.test.Samples;

public class AccountKeyTest {
	@Test public void testEncoding() throws BadFormatException {
		AccountKey ak=AccountKey.dummy("1234");
		Blob b=ak.getEncoding();
		AccountKey ak2=AccountKey.create(Samples.TEST_STORE.decode(b));
		
		assertEquals(ak,ak2);
	}
}
