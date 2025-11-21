package convex.core.crypto.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

public class Base58Test {

	@Test public void testBase58() {
		// Test vectors from: https://datatracker.ietf.org/doc/html/draft-msporny-base58-02
		assertEquals("Hello World!",new String(Base58.decode("2NEpo7TZRRrLZSi2U")));
		
		assertEquals("11233QC4",Base58.encode(Blob.parse("0x0000287fb4cd").getBytes()));
	}
}
