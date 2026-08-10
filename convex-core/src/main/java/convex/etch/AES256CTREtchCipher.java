package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import convex.core.util.Utils;

/** AES-256-CTR Etch overlay addressed by absolute file offset. */
final class AES256CTREtchCipher extends EtchFileCipher {
	private static final String TRANSFORMATION="AES/CTR/NoPadding";
	private static final int KEY_LENGTH=32;
	private static final int BLOCK_LENGTH=EtchConstants.V3_AES_BLOCK_SIZE;
	private static final SecretKey ZERO_KEY=new SecretKeySpec(new byte[KEY_LENGTH],"AES");
	private static final IvParameterSpec ZERO_IV=new IvParameterSpec(new byte[BLOCK_LENGTH]);

	private final OwnedSecretKey key;
	private final ThreadLocal<State> states;
	private final ArrayList<State> createdStates=new ArrayList<>();

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
		this.key=new OwnedSecretKey(keyBytes);
		this.states=ThreadLocal.withInitial(this::createState);
	}

	private State createState() {
		synchronized (this) {
			ensureActive();
			try {
				State state=new State(Cipher.getInstance(TRANSFORMATION));
				createdStates.add(state);
				return state;
			} catch (GeneralSecurityException e) {
				throw new IllegalStateException("AES-CTR is unavailable",e);
			}
		}
	}

	@Override
	void initialiseState(long fileOffset) throws IOException {
		if (fileOffset<0L) throw new IllegalArgumentException("Negative Etch cipher offset");
		states.get().initialise(fileOffset,key);
	}

	@Override
	void decryptState(ByteBuffer input, byte[] destination, int destinationOffset)
			throws IOException {
		int length=input.remaining();
		states.get().transform(input,ByteBuffer.wrap(destination,destinationOffset,length));
	}

	@Override
	void encryptState(byte[] source, int sourceOffset, ByteBuffer output) throws IOException {
		states.get().transform(ByteBuffer.wrap(source,sourceOffset,output.remaining()),output);
	}

	@Override
	long transformLongState(long fileOffset, long value) throws IOException {
		if (fileOffset<0L) throw new IllegalArgumentException("Negative Etch cipher offset");
		State state=states.get();
		state.initialise(fileOffset,key);
		return state.transformLong(value);
	}

	@Override
	void destroyState() {
		for (State state:createdStates) state.destroy();
		createdStates.clear();
		states.remove();
		key.destroy();
	}

	private static final class State {
		private final Cipher cipher;
		private final byte[] iv=new byte[EtchConstants.V3_CIPHER_LOCATOR_SIZE];
		private final byte[] skipInput=new byte[BLOCK_LENGTH-1];
		private final byte[] skipOutput=new byte[BLOCK_LENGTH-1];
		private final byte[] longInput=new byte[Long.BYTES];
		private final byte[] longOutput=new byte[Long.BYTES];
		private long position=-1L;

		private State(Cipher cipher) {
			this.cipher=cipher;
		}

		private void initialise(long fileOffset, SecretKey key) throws IOException {
			if (position==fileOffset) return;
			position=-1L;
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
			position=fileOffset;
		}

		private void transform(ByteBuffer input, ByteBuffer output) throws IOException {
			int length=input.remaining();
			if (output.remaining()<length) throw new IllegalArgumentException("Insufficient cipher output space");
			long nextPosition=Math.addExact(position,length);
			boolean success=false;
			try {
				int written=cipher.update(input,output);
				if ((written!=length)||input.hasRemaining()) {
					throw new IOException("AES-CTR provider buffered an unpadded stream transform");
				}
				success=true;
			} catch (ShortBufferException e) {
				throw new IOException("Insufficient AES-CTR output space",e);
			} finally {
				position=success?nextPosition:-1L;
			}
		}

		private long transformLong(long value) throws IOException {
			Utils.writeLong(longInput,0,value);
			long nextPosition=Math.addExact(position,Long.BYTES);
			boolean success=false;
			try {
				int written=cipher.update(longInput,0,Long.BYTES,longOutput,0);
				if (written!=Long.BYTES) throw new IOException("AES-CTR failed to transform an index slot");
				success=true;
			} catch (ShortBufferException e) {
				throw new IOException("Insufficient AES-CTR index output space",e);
			} finally {
				position=success?nextPosition:-1L;
			}
			return Utils.readLong(longOutput,0,Long.BYTES);
		}

		private void destroy() {
			position=-1L;
			try {
				cipher.init(Cipher.ENCRYPT_MODE,ZERO_KEY,ZERO_IV);
			} catch (GeneralSecurityException e) {
				// Best effort: the owned key and all Java-side state are still wiped.
			}
			Arrays.fill(iv,(byte)0);
			Arrays.fill(skipInput,(byte)0);
			Arrays.fill(skipOutput,(byte)0);
			Arrays.fill(longInput,(byte)0);
			Arrays.fill(longOutput,(byte)0);
		}
	}

	/** AES key whose owned encoding can be wiped deterministically. */
	private static final class OwnedSecretKey implements SecretKey {
		private static final long serialVersionUID=1L;
		private final byte[] encoded;
		private volatile boolean destroyed;

		private OwnedSecretKey(byte[] source) {
			this.encoded=source.clone();
		}

		@Override
		public String getAlgorithm() {
			return "AES";
		}

		@Override
		public String getFormat() {
			return "RAW";
		}

		@Override
		public byte[] getEncoded() {
			if (destroyed) throw new IllegalStateException("AES key is destroyed");
			return encoded.clone();
		}

		@Override
		public void destroy() {
			if (destroyed) return;
			destroyed=true;
			Arrays.fill(encoded,(byte)0);
		}

		@Override
		public boolean isDestroyed() {
			return destroyed;
		}
	}
}
