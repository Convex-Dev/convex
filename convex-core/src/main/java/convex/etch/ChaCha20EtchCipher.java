package convex.etch;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.bouncycastle.util.Pack;

import convex.core.util.Utils;

/** ChaCha20 Etch overlay addressed by absolute file offset. */
final class ChaCha20EtchCipher extends EtchFileCipher {
	private static final int KEY_LENGTH=32;
	private static final int ROUNDS=20;
	private static final int STATE_WORDS=16;
	private static final int COUNTER_WORD=12;
	private static final int NONCE_WORD=13;
	private static final int[] CONSTANTS={
			0x61707865,0x3320646e,0x79622d32,0x6b206574
	};

	private final int[] keyWords=new int[KEY_LENGTH/Integer.BYTES];
	private final ThreadLocal<State> states;

	static ChaCha20EtchCipher derive(byte[] secret, byte[] fileSalt) {
		byte[] derived=EtchKeyDerivation.deriveFileCipherKey(secret,fileSalt);
		try {
			return new ChaCha20EtchCipher(derived);
		} finally {
			Arrays.fill(derived,(byte)0);
		}
	}

	static ChaCha20EtchCipher fromKey(byte[] key) {
		return new ChaCha20EtchCipher(key);
	}

	private ChaCha20EtchCipher(byte[] key) {
		if ((key==null)||(key.length!=KEY_LENGTH)) {
			throw new IllegalArgumentException("ChaCha20 requires a 32-byte key");
		}
		for (int i=0;i<keyWords.length;i++) {
			keyWords[i]=Pack.littleEndianToInt(key,i*Integer.BYTES);
		}
		states=ThreadLocal.withInitial(State::new);
	}

	@Override
	public void initialise(long fileOffset) {
		if (fileOffset<0L) throw new IllegalArgumentException("Negative Etch cipher offset");
		states.get().initialise(fileOffset);
	}

	@Override
	public void decrypt(ByteBuffer input, byte[] destination, int destinationOffset) {
		states.get().decrypt(input,destination,destinationOffset);
	}

	@Override
	public void encrypt(byte[] source, int sourceOffset, ByteBuffer output) {
		states.get().encrypt(source,sourceOffset,output);
	}

	@Override
	public long transformLong(long fileOffset, long value) {
		initialise(fileOffset);
		return states.get().transformLong(value);
	}

	private final class State {
		private final byte[] locator=new byte[EtchConstants.V3_CIPHER_LOCATOR_SIZE];
		private final byte[] keyStream=new byte[EtchConstants.V3_CHACHA_BLOCK_SIZE];
		private final int[] inputState=new int[STATE_WORDS];
		private final int[] outputState=new int[STATE_WORDS];
		private long position=-1L;
		private int keyStreamOffset=EtchConstants.V3_CHACHA_BLOCK_SIZE;

		private State() {
			System.arraycopy(CONSTANTS,0,inputState,0,CONSTANTS.length);
			System.arraycopy(keyWords,0,inputState,CONSTANTS.length,keyWords.length);
		}

		private void initialise(long fileOffset) {
			if (position==fileOffset) return;
			position=fileOffset;
			keyStreamOffset=EtchConstants.V3_CHACHA_BLOCK_SIZE;
		}

		private void loadBlock() {
			keyStreamOffset=EtchCipherLocator.writeChaCha20(position,locator);
			inputState[COUNTER_WORD]=Utils.readInt(locator,EtchConstants.V3_CHACHA_NONCE_SIZE);
			for (int i=0;i<STATE_WORDS-NONCE_WORD;i++) {
				inputState[NONCE_WORD+i]=Pack.littleEndianToInt(locator,i*Integer.BYTES);
			}
			ChaChaEngine.chachaCore(ROUNDS,inputState,outputState);
			Pack.intToLittleEndian(outputState,keyStream,0);
		}

		private void decrypt(ByteBuffer input, byte[] destination, int destinationOffset) {
			int length=input.remaining();
			if ((destinationOffset<0)||(destinationOffset>destination.length-length)) {
				throw new IllegalArgumentException("Insufficient ChaCha20 output space");
			}
			while (input.hasRemaining()) {
				if (keyStreamOffset==keyStream.length) loadBlock();
				int count=Math.min(input.remaining(),keyStream.length-keyStreamOffset);
				int end=keyStreamOffset+count;
				while (keyStreamOffset+Long.BYTES<=end) {
					long mask=Utils.readLong(keyStream,keyStreamOffset,Long.BYTES);
					Utils.writeLong(destination,destinationOffset,input.getLong()^mask);
					destinationOffset+=Long.BYTES;
					keyStreamOffset+=Long.BYTES;
					position+=Long.BYTES;
				}
				while (keyStreamOffset<end) {
					destination[destinationOffset++]=(byte)(input.get()^keyStream[keyStreamOffset++]);
					position++;
				}
			}
		}

		private void encrypt(byte[] source, int sourceOffset, ByteBuffer output) {
			int length=output.remaining();
			if ((sourceOffset<0)||(sourceOffset>source.length-length)) {
				throw new IllegalArgumentException("Insufficient ChaCha20 input bytes");
			}
			while (output.hasRemaining()) {
				if (keyStreamOffset==keyStream.length) loadBlock();
				int count=Math.min(output.remaining(),keyStream.length-keyStreamOffset);
				int end=keyStreamOffset+count;
				while (keyStreamOffset+Long.BYTES<=end) {
					long mask=Utils.readLong(keyStream,keyStreamOffset,Long.BYTES);
					output.putLong(Utils.readLong(source,sourceOffset,Long.BYTES)^mask);
					sourceOffset+=Long.BYTES;
					keyStreamOffset+=Long.BYTES;
					position+=Long.BYTES;
				}
				while (keyStreamOffset<end) {
					output.put((byte)(source[sourceOffset++]^keyStream[keyStreamOffset++]));
					position++;
				}
			}
		}

		private long transformLong(long value) {
			if (keyStreamOffset==keyStream.length) loadBlock();
			if (keyStreamOffset+Long.BYTES>keyStream.length) {
				throw new IllegalStateException("Etch index slot crosses a ChaCha20 block boundary");
			}
			long transformed=value^Utils.readLong(keyStream,keyStreamOffset,Long.BYTES);
			keyStreamOffset+=Long.BYTES;
			position+=Long.BYTES;
			return transformed;
		}
	}
}
