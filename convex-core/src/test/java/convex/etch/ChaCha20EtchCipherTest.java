package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

/** Exact random-access and cursor tests for the Etch ChaCha20 overlay. */
public class ChaCha20EtchCipherTest {

	@Test
	public void testZeroKeyRandomAccessVectors() throws Exception {
		ChaCha20EtchCipher cipher=ChaCha20EtchCipher.fromKey(new byte[32]);
		assertVector(cipher,0L,
				"76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7");
		assertVector(cipher,1000L,
				"b95182dbc5eec042b89e22f11a085b739a3611cd8d836018c4fff0b86c02ed66");
		assertVector(cipher,EtchConstants.V3_CHACHA_NONCE_REGION_SIZE,
				"de9cba7bf3d69ef5e786dc63973f653a0b49e015adbff7134fcb7df137821031");
	}

	@Test
	public void testNonceRegionBoundaryStraddle() throws Exception {
		ChaCha20EtchCipher cipher=ChaCha20EtchCipher.fromKey(new byte[32]);
		long position=EtchConstants.V3_CHACHA_NONCE_REGION_SIZE-32L;
		assertArrayEquals(Utils.hexToBytes(
				"92c74f2f626c6a640c0b1284d839ec81f1696281dafc3e684593937023b58b1d"
				+"de9cba7bf3d69ef5e786dc63973f653a0b49e015adbff7134fcb7df137821031"),
				transform(cipher,position,new byte[64]));
	}

	@Test
	public void testRoundTripRandomAccessAndSplitCursor() throws Exception {
		byte[] key=new byte[32];
		for (int i=0;i<key.length;i++) key[i]=(byte)(i*7+3);
		ChaCha20EtchCipher cipher=ChaCha20EtchCipher.fromKey(key);
		byte[] plain=new byte[257];
		for (int i=0;i<plain.length;i++) plain[i]=(byte)(i*11+5);

		byte[] encrypted=transform(cipher,37L,plain);
		assertFalse(Arrays.equals(plain,encrypted));
		assertArrayEquals(plain,transform(cipher,37L,encrypted));

		int offset=73;
		int length=119;
		assertArrayEquals(Arrays.copyOfRange(encrypted,offset,offset+length),
				transform(cipher,37L+offset,Arrays.copyOfRange(plain,offset,offset+length)));

		byte[] splitOutput=new byte[length];
		EtchCipherCursor cursor=cipher.start(37L+offset);
		cursor.transform(ByteBuffer.wrap(plain,offset,17),ByteBuffer.wrap(splitOutput,0,17));
		cursor.transform(ByteBuffer.wrap(plain,offset+17,length-17),
				ByteBuffer.wrap(splitOutput,17,length-17));
		assertArrayEquals(Arrays.copyOfRange(encrypted,offset,offset+length),splitOutput);
	}

	@Test
	public void testAlignedLongTransform() throws Exception {
		ChaCha20EtchCipher cipher=ChaCha20EtchCipher.fromKey(new byte[32]);
		long value=0x0123456789abcdefL;
		for (long offset:new long[] {0L,8L,56L,64L,1000L}) {
			long encrypted=cipher.start(offset).transformLong(value);
			long decrypted=cipher.start(offset).transformLong(encrypted);
			org.junit.jupiter.api.Assertions.assertEquals(value,decrypted);
		}
	}

	private static byte[] transform(EtchFileCipher cipher, long offset, byte[] input)
			throws Exception {
		byte[] output=new byte[input.length];
		cipher.start(offset).transform(ByteBuffer.wrap(input),ByteBuffer.wrap(output));
		return output;
	}

	private static void assertVector(EtchFileCipher cipher, long offset, String expected)
			throws Exception {
		assertArrayEquals(Utils.hexToBytes(expected),transform(cipher,offset,new byte[32]));
	}
}
