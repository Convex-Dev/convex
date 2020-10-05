package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.util.Utils;

@RunWith(Parameterized.class)
public class ParamTestHash {
	private Hash hash;

	public ParamTestHash(String label, Hash data) {
		this.hash = data;
	}

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { "Empty bytes", Hash.sha256(Utils.EMPTY_BYTES) },
				{ "Short string data", Hash.sha256("Hello World") },
				{ "Length 2 strict sublist of byte data", Hash.sha256(new byte[] { 1, 2, 3, 4 }) },
				{ "Bitcoin genesis header block",
						Blob.fromHex(HashTest.GENESIS_HEADER).computeHash(Hash.getSHA256Digest()) } });
	}

	@Test
	public void testHexRoundTrip() {
		String hex = hash.toHexString();
		Hash d2 = Hash.fromHex(hex);
		assertEquals(hash, d2);
		assertEquals(hash.hashCode(), d2.hashCode());
	}

	@Test
	public void testSlice() {
		ABlob d = hash.slice(0, hash.length());
		assertEquals(hash.toBlob(), d);
	}
}
