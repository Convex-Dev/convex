package convex.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.crypto.Hash;
import convex.core.crypto.HashTest;
import convex.test.Samples;

@RunWith(Parameterized.class)
public class ParamTestBlobs {
	private ABlob data;

	public ParamTestBlobs(String label, ABlob data) {
		this.data = data;
	}

	private static Random rand = new Random(1234);

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { "Empty bytes", Blob.wrap(new byte[0]) },
				{ "Short hex string CAFEBABE", Blob.fromHex("CAFEBABE") },
				{ "Long random BlobTree", BlobTree.create(Blob.createRandom(rand, 10000)) },
				{ "Length 2 strict sublist of byte data", Blob.create(new byte[] { 1, 2, 3, 4 }, 1, 2) },
				{ "Bitcoin genesis header block", Blob.fromHex(HashTest.GENESIS_HEADER) },
				{ "Full Blob of random data", Samples.FULL_BLOB }, { "Big blob", Samples.BIG_BLOB_TREE } });
	}

	@Test
	public void testHexRoundTrip() {
		String hex = data.toHexString();
		ABlob d2 = Blobs.fromHex(hex);
		assertEquals(data, d2);
		assertEquals(data.hashCode(), d2.hashCode());
	}

	@Test
	public void testHashCache() {
		// hashcode should be based on hash of blob representation
		Hash hash = data.getEncoding().getContentHash();
		assertEquals(data.hashCode(), hash.hashCode());
	}

	@Test
	public void testSlice() {
		ABlob d = data.slice(0, data.length());
		assertEquals(data, d);
	}

	@Test
	public void testCompare() {
		long len = data.length();
		assertEquals(0, data.compareTo(data));
		assertEquals(0, data.compareTo(Blob.create(data.getBytes())));

		assertTrue(data.compareTo(data.append(Samples.ONE_ZERO_BYTE_DATA)) < 0);

		if (len > 0) {
			// anything should be "larger" than empty data
			assertTrue(data.compareTo(Blob.EMPTY) > 0);
			assertTrue(Blob.EMPTY.compareTo(data) < 0);
		}

	}
}
