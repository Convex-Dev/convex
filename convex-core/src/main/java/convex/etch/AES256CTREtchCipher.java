package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import convex.core.util.Utils;

/** AES-256-CTR Etch overlay addressed by absolute file offset. */
final class AES256CTREtchCipher implements EtchFileCipher {
	private static final String TRANSFORMATION="AES/CTR/NoPadding";
	private static final int KEY_LENGTH=32;
	private static final int BLOCK_LENGTH=EtchConstants.V3_AES_BLOCK_SIZE;

	private final SecretKeySpec key;
	private final ThreadLocal<State> states;

	static AES256CTREtchCipher derive(byte[] secret, byte[] fileSalt) {
		byte[] derived=EtchKeyDerivation.deriveFileCipherKey(secret,fileSalt);
		try {
			return new AES256CTREtchCipher(derived);
		} finally {
			Arrays.fill(derived,(byte)0);
		}
	}

	static AES256CTREtchCipher fromKey(byte[] key) {
		return new AES256CTREtchCipher(key);
	}

	private AES256CTREtchCipher(byte[] keyBytes) {
		if ((keyBytes==null)||(keyBytes.length!=KEY_LENGTH)) {
			throw new IllegalArgumentException("AES-256 requires a 32-byte key");
		}
		this.key=new SecretKeySpec(keyBytes,"AES");
		this.states=ThreadLocal.withInitial(this::createState);
	}

	private State createState() {
		try {
			return new State(Cipher.getInstance(TRANSFORMATION));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("AES-CTR is unavailable",e);
		}
	}

	@Override
	public void initialise(long fileOffset) throws IOException {
		if (fileOffset<0L) throw new IllegalArgumentException("Negative Etch cipher offset");
		states.get().initialise(fileOffset);
	}

	@Override
	public void decrypt(ByteBuffer input, byte[] destination, int destinationOffset)
			throws IOException {
		int length=input.remaining();
		states.get().transform(input,ByteBuffer.wrap(destination,destinationOffset,length));
	}

	@Override
	public void encrypt(byte[] source, int sourceOffset, ByteBuffer output) throws IOException {
		states.get().transform(ByteBuffer.wrap(source,sourceOffset,output.remaining()),output);
	}

	@Override
	public long transformLong(long fileOffset, long value) throws IOException {
		initialise(fileOffset);
		return states.get().transformLong(value);
	}

	private final class State {
		private final Cipher cipher;
		private final byte[] iv=new byte[EtchConstants.V3_CIPHER_LOCATOR_SIZE];
		private final byte[] skipInput=new byte[BLOCK_LENGTH-1];
		private final byte[] skipOutput=new byte[BLOCK_LENGTH-1];
		private final byte[] longInput=new byte[Long.BYTES];
		private final byte[] longOutput=new byte[Long.BYTES];

		private State(Cipher cipher) {
			this.cipher=cipher;
		}

		private void initialise(long fileOffset) throws IOException {
			int skip=EtchCipherLocator.writeAES(fileOffset,iv);
			try {
				cipher.init(Cipher.ENCRYPT_MODE,key,new IvParameterSpec(iv));
				if (skip>0) {
					int written=cipher.update(skipInput,0,skip,skipOutput,0);
					if (written!=skip) throw new IOException("AES-CTR failed to seek within a block");
				}
			} catch (GeneralSecurityException e) {
				throw new IOException("Failed to initialise AES-CTR",e);
			}
		}

		private void transform(ByteBuffer input, ByteBuffer output) throws IOException {
			int length=input.remaining();
			if (output.remaining()<length) throw new IllegalArgumentException("Insufficient cipher output space");
			try {
				int written=cipher.update(input,output);
				if ((written!=length)||input.hasRemaining()) {
					throw new IOException("AES-CTR provider buffered an unpadded stream transform");
				}
			} catch (ShortBufferException e) {
				throw new IOException("Insufficient AES-CTR output space",e);
			}
		}

		private long transformLong(long value) throws IOException {
			Utils.writeLong(longInput,0,value);
			try {
				int written=cipher.update(longInput,0,Long.BYTES,longOutput,0);
				if (written!=Long.BYTES) throw new IOException("AES-CTR failed to transform an index slot");
			} catch (ShortBufferException e) {
				throw new IOException("Insufficient AES-CTR index output space",e);
			}
			return Utils.readLong(longOutput,0,Long.BYTES);
		}
	}
}
