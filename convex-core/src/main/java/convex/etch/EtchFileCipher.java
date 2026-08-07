package convex.etch;

import java.io.IOException;

/** Random-access, length-preserving Etch file cipher. */
interface EtchFileCipher {
	/**
	 * Starts a cursor at an absolute byte offset from the beginning of the file.
	 */
	EtchCipherCursor start(long fileOffset) throws IOException;
}
