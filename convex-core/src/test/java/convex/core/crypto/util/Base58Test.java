package convex.core.crypto.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

public class Base58Test {

	@Test public void testBase58() {
		// Test vectors from: https://datatracker.ietf.org/doc/html/draft-msporny-base58-02
		assertEquals("Hello World!",new String(Base58.decode("2NEpo7TZRRrLZSi2U")));
		
		assertEquals("11233QC4",Base58.encode(Blob.parse("0x0000287fb4cd").getBytes()));
	}

	@Test
	public void testTooShortForChecksum() {
		// Decodes to fewer bytes than the checksum length: must say so, rather than
		// failing inside a range computation
		IllegalArgumentException e=assertThrows(IllegalArgumentException.class,
				()->Base58Check.decode("1"));
		assertTrue(e.getMessage().contains("checksum"),e.getMessage());
	}

}
