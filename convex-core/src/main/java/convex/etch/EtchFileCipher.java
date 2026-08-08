package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Random-access, length-preserving Etch file cipher. */
abstract class EtchFileCipher {
	/** Initialises the current thread's reusable state at an absolute file offset. */
	abstract void initialise(long fileOffset) throws IOException;

	/** Decrypts mapped input directly into a byte array. */
	abstract void decrypt(ByteBuffer input, byte[] destination, int destinationOffset) throws IOException;

	/** Encrypts a byte array directly into mapped output. */
	abstract void encrypt(byte[] source, int sourceOffset, ByteBuffer output) throws IOException;

	/** Transforms one independently addressed index slot. */
	abstract long transformLong(long fileOffset, long value) throws IOException;
}
