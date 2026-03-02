package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

/**
 * Tests for HKDF (RFC 5869) with SHA-256.
 */
public class HKDFTest {

	// RFC 5869 Test Case 1
	@Test
	public void testRFC5869Case1() {
		byte[] ikm = Utils.hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
		byte[] salt = Utils.hexToBytes("000102030405060708090a0b0c");
		byte[] info = Utils.hexToBytes("f0f1f2f3f4f5f6f7f8f9");
		int length = 42;

		byte[] okm = HKDF.derive(ikm, salt, info, length);

		String expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
		assertEquals(expected, Utils.toHexString(okm));
	}

	// RFC 5869 Test Case 2
	@Test
	public void testRFC5869Case2() {
		byte[] ikm = Utils.hexToBytes(
				"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
				"202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
				"404142434445464748494a4b4c4d4e4f");
		byte[] salt = Utils.hexToBytes(
				"606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
				"808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
				"a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
		byte[] info = Utils.hexToBytes(
				"b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
				"d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
				"f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
		int length = 82;

		byte[] okm = HKDF.derive(ikm, salt, info, length);

		String expected =
				"b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
				"59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
				"cc30c58179ec3e87c14c01d5c1f3434f1d87";
		assertEquals(expected, Utils.toHexString(okm));
	}

	// RFC 5869 Test Case 3 — null salt and empty info
	@Test
	public void testRFC5869Case3() {
		byte[] ikm = Utils.hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
		byte[] salt = null;
		byte[] info = new byte[0];
		int length = 42;

		byte[] okm = HKDF.derive(ikm, salt, info, length);

		String expected = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8";
		assertEquals(expected, Utils.toHexString(okm));
	}

	@Test
	public void testDerive256() {
		byte[] ikm = "some input keying material".getBytes();
		byte[] result = HKDF.derive256(ikm, null, null);
		assertEquals(32, result.length);
	}

	@Test
	public void testDifferentInfoProducesDifferentOutput() {
		byte[] ikm = "shared secret".getBytes();
		byte[] out1 = HKDF.derive256(ikm, null, "context-a".getBytes());
		byte[] out2 = HKDF.derive256(ikm, null, "context-b".getBytes());
		assertFalse(java.util.Arrays.equals(out1, out2));
	}

	@Test
	public void testDifferentSaltProducesDifferentOutput() {
		byte[] ikm = "shared secret".getBytes();
		byte[] out1 = HKDF.derive256(ikm, "salt-a".getBytes(), null);
		byte[] out2 = HKDF.derive256(ikm, "salt-b".getBytes(), null);
		assertFalse(java.util.Arrays.equals(out1, out2));
	}

	@Test
	public void testNullIkmThrows() {
		assertThrows(IllegalArgumentException.class, () -> HKDF.derive(null, null, null, 32));
	}

	@Test
	public void testZeroLengthThrows() {
		assertThrows(IllegalArgumentException.class, () -> HKDF.derive("ikm".getBytes(), null, null, 0));
	}
}
