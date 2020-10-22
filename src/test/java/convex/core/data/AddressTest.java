package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

public class AddressTest {

	@Test
	public void testChecksumRoundTrip() {
		Random r = new Random(1585875);
		for (int i = 0; i < 10; i++) {
			Blob ba = Blob.createRandom(r, Address.LENGTH);
			Address a = Address.wrap(ba.getBytes());

			String s = a.toChecksumHex();
			assertEquals(a, Address.fromHex(s));
			assertEquals(a, Address.fromChecksumHex(s));

			String sl = s.toLowerCase();

			assertEquals(a, Address.fromHex(sl));
			assertThrows(IllegalArgumentException.class, () -> Address.fromChecksumHex(sl));
		}
	}


	@Test
	public void testEquality() {
		String aString = Blob.createRandom(new Random(), Address.LENGTH).toHexString();
		Address a = Address.fromHex(aString);
		assertEquals(a, Address.fromHex(aString));

		// Address should not be equal to Blob with same byte content
		Blob b = a.toBlob();
		assertNotEquals(a, b);

		assertEquals(0, a.compareTo(b));
		
		BlobsTest.doBlobTests(a);			
	}
}
