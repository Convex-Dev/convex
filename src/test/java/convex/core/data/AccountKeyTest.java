package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

public class AccountKeyTest {

	@Test
	public void testChecksumRoundTrip() {
		Random r = new Random(1585875);
		for (int i = 0; i < 10; i++) {
			Blob ba = Blob.createRandom(r, AccountKey.LENGTH);
			AccountKey a = AccountKey.wrap(ba.getBytes());

			String s = a.toChecksumHex();
			assertEquals(a, AccountKey.fromHex(s));
			assertEquals(a, AccountKey.fromChecksumHex(s));

			String sl = s.toLowerCase();

			assertEquals(a, AccountKey.fromHex(sl));
			assertThrows(IllegalArgumentException.class, () -> AccountKey.fromChecksumHex(sl));
		}
	}


	@Test
	public void testEquality() {
		String aString = Blob.createRandom(new Random(), AccountKey.LENGTH).toHexString();
		AccountKey a = AccountKey.fromHex(aString);
		assertEquals(a, AccountKey.fromHex(aString));

		// AccountKey should not be equal to Blob with same byte content
		Blob b = a.toBlob();
		assertEquals(a, b);

		// AccountKey has comparison equality with Blob
		assertEquals(0, a.compareTo(b));
		
		BlobsTest.doBlobTests(a);			
	}
}
