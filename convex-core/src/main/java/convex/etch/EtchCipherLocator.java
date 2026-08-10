package convex.etch;

import java.util.Arrays;

import convex.core.util.Utils;

/** Canonical v3 mapping from an absolute file offset to a cipher-block locator. */
final class EtchCipherLocator {
	private static final int BLOCK_NUMBER_OFFSET=
			EtchConstants.V3_CIPHER_LOCATOR_SIZE-Long.BYTES;

	private EtchCipherLocator() {
	}

	/** Writes the AES block locator and returns the byte offset within that block. */
	static int writeAES(long fileOffset, byte[] destination) {
		return write(fileOffset,EtchConstants.V3_AES_BLOCK_SHIFT,destination);
	}

	/** Writes the ChaCha20 block locator and returns the byte offset within that block. */
	static int writeChaCha20(long fileOffset, byte[] destination) {
		return write(fileOffset,EtchConstants.V3_CHACHA_BLOCK_SHIFT,destination);
	}

	private static int write(long fileOffset, int blockShift, byte[] destination) {
		if (fileOffset<0L) throw new IllegalArgumentException("Negative Etch cipher offset");
		if ((destination==null)
				||(destination.length!=EtchConstants.V3_CIPHER_LOCATOR_SIZE)) {
			throw new IllegalArgumentException("Etch cipher locator must be exactly 16 bytes");
		}
		Arrays.fill(destination,(byte)0);
		Utils.writeLong(destination,BLOCK_NUMBER_OFFSET,fileOffset>>>blockShift);
		return (int)(fileOffset&((1L<<blockShift)-1L));
	}
}
