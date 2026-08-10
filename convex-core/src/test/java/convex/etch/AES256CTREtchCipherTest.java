package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

public class AES256CTREtchCipherTest {
	private static final long ONE_TEBIBYTE=4L*EtchConstants.V3_CHACHA_NONCE_REGION_SIZE;

	@Test
	public void testKnownZeroKeyBlock() throws Exception {
		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		byte[] actual=transform(cipher,0L,new byte[16]);
		assertArrayEquals(Utils.hexToBytes("dc95c078a2408989ad48a21492842087"),actual);
	}

	@Test
	public void testKnownZeroKeyRandomAccessVectors() throws Exception {
		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		assertVector(cipher,0L,
				"dc95c078a2408989ad48a21492842087530f8afbc74536b9a963b4f1c4cb738b");
		assertVector(cipher,1000L,
				"8afd0dbc2a4d423756a368c7a34325e4adce918732e8ea7e60aba678a506608d");
		assertVector(cipher,EtchConstants.V3_CHACHA_NONCE_REGION_SIZE,
				"acf2e0a693fbbcba4d41b861e0d89e37ef7b5eb3dfdefba752bcc4283a9aee4d");
		assertVector(cipher,ONE_TEBIBYTE,
				"486d8c193db1ed73acb17990442fc40b323a15c8e5113b2641cc9b30c34c4f62");
	}

	@Test
	public void testRandomAccessAndSplitOperation() throws Exception {
		byte[] key=new byte[32];
		for (int i=0;i<key.length;i++) key[i]=(byte)(i*7+3);
		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(key);
		byte[] plain=new byte[257];
		for (int i=0;i<plain.length;i++) plain[i]=(byte)(i*11+5);

		byte[] encrypted=transform(cipher,0L,plain);
		assertFalse(Arrays.equals(plain,encrypted));
		assertArrayEquals(plain,transform(cipher,0L,encrypted));

		int offset=37;
		int length=113;
		byte[] slice=Arrays.copyOfRange(plain,offset,offset+length);
		assertArrayEquals(Arrays.copyOfRange(encrypted,offset,offset+length),
				transform(cipher,offset,slice));

		byte[] splitOutput=new byte[length];
		cipher.initialise(offset);
		cipher.encrypt(slice,0,ByteBuffer.wrap(splitOutput,0,7));
		cipher.encrypt(slice,7,ByteBuffer.wrap(splitOutput,7,length-7));
		assertArrayEquals(Arrays.copyOfRange(encrypted,offset,offset+length),splitOutput);
	}

	@Test
	public void testLongTransformAtAlignedOffsets() throws Exception {
		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		long value=0x0123456789abcdefL;
		for (long offset:new long[] { 0L,8L,16L,24L,ONE_TEBIBYTE }) {
			long encrypted=cipher.transformLong(offset,value);
			long decrypted=cipher.transformLong(offset,encrypted);
			org.junit.jupiter.api.Assertions.assertEquals(value,decrypted);
		}
	}

	@Test
	public void testKeyValidation() {
		assertThrows(IllegalArgumentException.class,()->AES256CTREtchCipher.fromKey(null));
		assertThrows(IllegalArgumentException.class,()->AES256CTREtchCipher.fromKey(new byte[31]));
	}

	private static byte[] transform(EtchFileCipher cipher, long offset, byte[] input) throws Exception {
		byte[] output=new byte[input.length];
		cipher.initialise(offset);
		cipher.encrypt(input,0,ByteBuffer.wrap(output));
		return output;
	}

	private static void assertVector(EtchFileCipher cipher, long offset, String expected)
			throws Exception {
		assertArrayEquals(Utils.hexToBytes(expected),transform(cipher,offset,new byte[32]));
	}
}
