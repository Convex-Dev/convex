package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

/** Exact random-access and split-operation tests for the Etch ChaCha20 overlay. */
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
	public void testRoundTripRandomAccessAndSplitOperation() throws Exception {
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
		cipher.initialise(37L+offset);
		cipher.encrypt(plain,offset,ByteBuffer.wrap(splitOutput,0,17));
		cipher.encrypt(plain,offset+17,ByteBuffer.wrap(splitOutput,17,length-17));
		assertArrayEquals(Arrays.copyOfRange(encrypted,offset,offset+length),splitOutput);
	}

	@Test
	public void testAlignedLongTransform() throws Exception {
		ChaCha20EtchCipher cipher=ChaCha20EtchCipher.fromKey(new byte[32]);
		long value=0x0123456789abcdefL;
		for (long offset:new long[] {0L,8L,56L,64L,1000L}) {
			long encrypted=cipher.transformLong(offset,value);
			long decrypted=cipher.transformLong(offset,encrypted);
			org.junit.jupiter.api.Assertions.assertEquals(value,decrypted);
		}
	}

	/**
	 * A keystream block cut off by an Error is computed again on the next use.
	 * The Error comes from inside the block function: a copy of the cipher,
	 * defined by {@link RedirectingLoader}, calls {@link InterruptibleChaChaCore}
	 * where the production class calls the ChaCha20 core directly.
	 */
	@Test
	public void testBlockCutOffByErrorIsComputedAgain() throws Exception {
		byte[] key=new byte[32];
		key[0]=7;
		ChaCha20EtchCipher reference=ChaCha20EtchCipher.fromKey(key);
		RedirectingLoader loader=new RedirectingLoader();
		Method fromKey=loader.loadClass(ChaCha20EtchCipher.class.getName())
				.getDeclaredMethod("fromKey",byte[].class);
		fromKey.setAccessible(true);
		Object cipher=fromKey.invoke(null,(Object)key);
		Method transformLong=loader.loadClass(EtchFileCipher.class.getName())
				.getDeclaredMethod("transformLong",long.class,long.class);
		transformLong.setAccessible(true);
		long value=0x0123456789abcdefL;

		// Load the first block, then cut off loading the second
		assertEquals(reference.transformLong(0L,value),(long)transformLong.invoke(cipher,0L,value));
		assertTrue(loader.redirected,"The cipher no longer calls the ChaCha20 core directly");
		InterruptibleChaChaCore.FAIL_NEXT.set(true);
		InvocationTargetException cutOff=assertThrows(InvocationTargetException.class,
				()->transformLong.invoke(cipher,64L,value));
		assertInstanceOf(StackOverflowError.class,cutOff.getCause());
		assertEquals(reference.transformLong(64L,value),(long)transformLong.invoke(cipher,64L,value));
	}

	/** Block function the redirected cipher calls; throws once when asked to. */
	public static final class InterruptibleChaChaCore {
		static final AtomicBoolean FAIL_NEXT=new AtomicBoolean();

		public static void chachaCore(int rounds, int[] input, int[] output) {
			if (FAIL_NEXT.getAndSet(false)) throw new StackOverflowError("Simulated");
			ChaChaEngine.chachaCore(rounds,input,output);
		}
	}

	/**
	 * Defines its own copy of the ChaCha20 cipher classes, redirecting their calls
	 * to the ChaCha20 core to {@link InterruptibleChaChaCore}. The cipher's
	 * package-private superclass and helpers and its nest mate come from this
	 * loader too, so the copy links among itself; all else is the parent's.
	 */
	private static final class RedirectingLoader extends ClassLoader {
		private static final Set<String> COPIED=Set.of(
				EtchFileCipher.class.getName(),EtchCipherLocator.class.getName(),
				ChaCha20EtchCipher.class.getName(),ChaCha20EtchCipher.class.getName()+"$State");
		private static final byte[] CORE=utf8Constant(ChaChaEngine.class.getName());
		private static final byte[] REDIRECT=utf8Constant(InterruptibleChaChaCore.class.getName());

		private volatile boolean redirected;

		private RedirectingLoader() {
			super(ChaCha20EtchCipherTest.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!COPIED.contains(name)) return super.loadClass(name,resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> copy=findLoadedClass(name);
				if (copy==null) {
					byte[] bytes=redirect(classBytes(name));
					copy=defineClass(name,bytes,0,bytes.length);
				}
				if (resolve) resolveClass(copy);
				return copy;
			}
		}

		private static byte[] classBytes(String name) throws ClassNotFoundException {
			String resource=name.substring(name.lastIndexOf('.')+1)+".class";
			try (InputStream in=ChaCha20EtchCipher.class.getResourceAsStream(resource)) {
				if (in==null) throw new ClassNotFoundException(name);
				return in.readAllBytes();
			} catch (IOException e) {
				throw new ClassNotFoundException(name,e);
			}
		}

		/** Replaces the class-name constant of the ChaCha20 core, if present. */
		private byte[] redirect(byte[] bytes) {
			// A constant-pool string is its tag, length and bytes, and nothing in a
			// class file addresses the pool by byte offset, so one can change length.
			int at=indexOf(bytes,CORE);
			if (at<0) return bytes;
			redirected=true;
			byte[] result=new byte[bytes.length-CORE.length+REDIRECT.length];
			System.arraycopy(bytes,0,result,0,at);
			System.arraycopy(REDIRECT,0,result,at,REDIRECT.length);
			System.arraycopy(bytes,at+CORE.length,result,at+REDIRECT.length,bytes.length-at-CORE.length);
			return result;
		}

		/** Encodes an internal class name as a CONSTANT_Utf8 entry. */
		private static byte[] utf8Constant(String className) {
			byte[] name=className.replace('.','/').getBytes(StandardCharsets.US_ASCII);
			byte[] entry=new byte[name.length+3];
			entry[0]=1;
			entry[1]=(byte)(name.length>>8);
			entry[2]=(byte)name.length;
			System.arraycopy(name,0,entry,3,name.length);
			return entry;
		}

		private static int indexOf(byte[] bytes, byte[] target) {
			for (int i=0;i<=bytes.length-target.length;i++) {
				if (Arrays.equals(bytes,i,i+target.length,target,0,target.length)) return i;
			}
			return -1;
		}
	}

	private static byte[] transform(EtchFileCipher cipher, long offset, byte[] input)
			throws Exception {
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
