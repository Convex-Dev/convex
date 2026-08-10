package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Random-access, length-preserving Etch file cipher. */
abstract class EtchFileCipher {
	private volatile boolean destroyed;

	/**
	 * Positions the current thread's reusable state at an absolute file offset.
	 * Implementations must make this a no-op when already positioned there.
	 */
	final void initialise(long fileOffset) throws IOException {
		ensureActive();
		initialiseState(fileOffset);
	}

	abstract void initialiseState(long fileOffset) throws IOException;

	/**
	 * Decrypts mapped input directly into a byte array. The caller must first
	 * initialise this cipher for the input's absolute file offset; that operation
	 * also performs the lifecycle check for this hot path.
	 */
	final void decrypt(ByteBuffer input, byte[] destination, int destinationOffset) throws IOException {
		decryptState(input,destination,destinationOffset);
	}

	abstract void decryptState(ByteBuffer input, byte[] destination, int destinationOffset) throws IOException;

	/**
	 * Encrypts a byte array directly into mapped output. The caller must first
	 * initialise this cipher for the output's absolute file offset; that operation
	 * also performs the lifecycle check for this hot path.
	 */
	final void encrypt(byte[] source, int sourceOffset, ByteBuffer output) throws IOException {
		encryptState(source,sourceOffset,output);
	}

	abstract void encryptState(byte[] source, int sourceOffset, ByteBuffer output) throws IOException;

	/** Transforms one independently addressed index slot. */
	final long transformLong(long fileOffset, long value) throws IOException {
		ensureActive();
		return transformLongState(fileOffset,value);
	}

	abstract long transformLongState(long fileOffset, long value) throws IOException;

	/**
	 * Wipes all cipher-owned key and operation state. Callers must quiesce file
	 * operations before destruction, as they already must before closing a mapper.
	 */
	final synchronized void destroy() {
		if (destroyed) return;
		destroyed=true;
		destroyState();
	}

	abstract void destroyState();

	final boolean isDestroyed() {
		return destroyed;
	}

	final void ensureActive() {
		if (destroyed) throw new IllegalStateException("Etch file cipher is destroyed");
	}
}
