package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

public class AES256CTREtchCipherTest {
	@Test
	public void testKnownZeroKeyBlock() throws Exception {
		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		byte[] actual=transform(cipher,0L,new byte[16]);
		assertArrayEquals(Utils.hexToBytes("dc95c078a2408989ad48a21492842087"),actual);
	}

	@Test
	public void testRandomAccessAndSplitCursor() throws Exception {
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

		EtchCipherCursor cursor=cipher.start(offset);
		byte[] splitOutput=new byte[length];
		cursor.transform(ByteBuffer.wrap(slice,0,7),ByteBuffer.wrap(splitOutput,0,7));
		cursor.transform(ByteBuffer.wrap(slice,7,length-7),ByteBuffer.wrap(splitOutput,7,length-7));
		assertArrayEquals(Arrays.copyOfRange(encrypted,offset,offset+length),splitOutput);
	}

	@Test
	public void testLongTransformAtAlignedOffsets() throws Exception {
		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		long value=0x0123456789abcdefL;
		for (long offset:new long[] { 0L,8L,16L,24L,1L<<40 }) {
			long encrypted=cipher.start(offset).transformLong(value);
			long decrypted=cipher.start(offset).transformLong(encrypted);
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
		cipher.start(offset).transform(ByteBuffer.wrap(input),ByteBuffer.wrap(output));
		return output;
	}
}
