package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.util.Utils;

public class HashParamTest {

	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] {
				{ "Empty bytes", Hashing.sha256(Utils.EMPTY_BYTES) },
				{ "Short string data", Hashing.sha256("Hello World") },
				{ "Length 2 strict sublist of byte data", Hashing.sha256(new byte[] { 1, 2, 3, 4 }) },
				{ "Bitcoin genesis header block", Blob.fromHex(HashTest.GENESIS_HEADER).computeHash(Hashing.getSHA256Digest()) } });
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testHexRoundTrip(String label, Hash hash) {
		String hex = hash.toHexString();
		Hash d2 = Hash.fromHex(hex);
		assertEquals(hash, d2);
		assertEquals(hash.hashCode(), d2.hashCode());
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testSlice(String label, Hash hash) {
		ABlob d = hash.slice(0, hash.count());
		assertEquals(hash.toFlatBlob(), d);
	}
}
