package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.bouncycastle.util.Pack;
import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

/** Byte-exact vectors for the common v3 cipher-block locator. */
public class EtchCipherLocatorTest {
	private static final long JAVA_SIGNED_COUNTER_BOUNDARY=
			EtchConstants.V3_CHACHA_NONCE_REGION_SIZE/2L;
	private static final long ONE_TEBIBYTE=4L*EtchConstants.V3_CHACHA_NONCE_REGION_SIZE;
	private static final int CHACHA_STATE_WORDS=16;
	private static final int CHACHA_COUNTER_STATE_WORD=12;
	private static final int CHACHA_NONCE_STATE_WORD=13;
	private static final int[] CHACHA_CONSTANTS={
			0x61707865,0x3320646e,0x79622d32,0x6b206574
	};

	@Test
	public void testCanonicalLocatorStates() {
		assertState(0L,
				"00000000000000000000000000000000",0,
				"00000000000000000000000000000000",0);
		assertState(1000L,
				"0000000000000000000000000000003e",8,
				"0000000000000000000000000000000f",40);
		assertState(1024L,
				"00000000000000000000000000000040",0,
				"00000000000000000000000000000010",0);
		assertState(EtchConstants.V3_CHACHA_NONCE_REGION_SIZE,
				"00000000000000000000000400000000",0,
				"00000000000000000000000100000000",0);
		assertState(ONE_TEBIBYTE,
				"00000000000000000000001000000000",0,
				"00000000000000000000000400000000",0);
		assertState(Long.MAX_VALUE,
				"000000000000000007ffffffffffffff",15,
				"000000000000000001ffffffffffffff",63);
	}

	@Test
	public void testJavaSignedCounterRepresentationBoundary() {
		assertState(JAVA_SIGNED_COUNTER_BOUNDARY,
				"00000000000000000000000200000000",0,
				"00000000000000000000000080000000",0);
		assertChaChaVector(JAVA_SIGNED_COUNTER_BOUNDARY,32,
				"7a73bf222d4c436578ea504cf5cc10c51e0c4c80acdc5febcfeb046e5f3616a3");
	}

	@Test
	public void testChaCha20ZeroKeyKeystreamVectors() {
		assertChaChaVector(0L,32,
				"76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7");
		assertChaChaVector(1000L,32,
				"b95182dbc5eec042b89e22f11a085b739a3611cd8d836018c4fff0b86c02ed66");
		// Counter carry into the 96-bit nonce at the 256 GiB boundary.
		assertChaChaVector(EtchConstants.V3_CHACHA_NONCE_REGION_SIZE,32,
				"de9cba7bf3d69ef5e786dc63973f653a0b49e015adbff7134fcb7df137821031");
		assertChaChaVector(EtchConstants.V3_CHACHA_NONCE_REGION_SIZE-32L,64,
				"92c74f2f626c6a640c0b1284d839ec81f1696281dafc3e684593937023b58b1d"
				+"de9cba7bf3d69ef5e786dc63973f653a0b49e015adbff7134fcb7df137821031");
	}

	@Test
	public void testRejectsInvalidLocatorInputs() {
		assertThrows(IllegalArgumentException.class,
				()->EtchCipherLocator.writeAES(-1L,new byte[16]));
		assertThrows(IllegalArgumentException.class,
				()->EtchCipherLocator.writeAES(0L,null));
		assertThrows(IllegalArgumentException.class,
				()->EtchCipherLocator.writeChaCha20(0L,new byte[15]));
	}

	private static void assertState(long fileOffset, String expectedAES, int expectedAESByte,
			String expectedChaCha, int expectedChaChaByte) {
		byte[] locator=new byte[EtchConstants.V3_CIPHER_LOCATOR_SIZE];
		assertEquals(expectedAESByte,EtchCipherLocator.writeAES(fileOffset,locator));
		assertArrayEquals(Utils.hexToBytes(expectedAES),locator);
		assertEquals(expectedChaChaByte,EtchCipherLocator.writeChaCha20(fileOffset,locator));
		assertArrayEquals(Utils.hexToBytes(expectedChaCha),locator);
	}

	private static void assertChaChaVector(long fileOffset, int length, String expected) {
		assertArrayEquals(Utils.hexToBytes(expected),zeroKeyChaChaKeystream(fileOffset,length));
	}

	/**
	 * Reference construction using Bouncy Castle's reviewed ChaCha core. The
	 * locator split and boundary carry remain explicit test code so a provider's
	 * counter or nonce conventions cannot redefine the Etch format.
	 */
	private static byte[] zeroKeyChaChaKeystream(long fileOffset, int length) {
		byte[] result=new byte[length];
		byte[] locator=new byte[EtchConstants.V3_CIPHER_LOCATOR_SIZE];
		byte[] block=new byte[EtchConstants.V3_CHACHA_BLOCK_SIZE];
		int resultOffset=0;
		long position=fileOffset;
		while (resultOffset<length) {
			int byteInBlock=EtchCipherLocator.writeChaCha20(position,locator);
			int[] state=new int[CHACHA_STATE_WORDS];
			System.arraycopy(CHACHA_CONSTANTS,0,state,0,CHACHA_CONSTANTS.length);
			// State words 4..11 are the fixed all-zero 256-bit test key.
			// The locator is canonical big-endian: decode its low 32 bits as an
			// unsigned counter value before ChaCha stores that value in a word.
			state[CHACHA_COUNTER_STATE_WORD]=Utils.readInt(locator,
					EtchConstants.V3_CHACHA_NONCE_SIZE);
			for (int i=0;i<CHACHA_STATE_WORDS-CHACHA_NONCE_STATE_WORD;i++) {
				state[CHACHA_NONCE_STATE_WORD+i]=
						Pack.littleEndianToInt(locator,i*Integer.BYTES);
			}
			int[] output=new int[CHACHA_STATE_WORDS];
			ChaChaEngine.chachaCore(20,state,output);
			Pack.intToLittleEndian(output,block,0);

			int count=Math.min(length-resultOffset,block.length-byteInBlock);
			System.arraycopy(block,byteInBlock,result,resultOffset,count);
			resultOffset+=count;
			position+=count;
		}
		return result;
	}
}
