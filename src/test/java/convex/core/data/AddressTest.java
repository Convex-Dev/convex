package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AddressTest {

	@Test
	public void testAddress1() {
		Address a1=Address.create(1);
		assertEquals("#1",a1.toString());
		String hex="0000000000000001";
		assertEquals(hex,a1.toHexString());
		
		assertEquals(a1,Address.fromHex(hex));
		assertTrue(a1.compareTo(Blob.fromHex(hex))==0);
	}
}
