package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Stateful cursor through a file cipher's keystream.
 *
 * <p>A cursor is initialised once at an absolute file offset and may transform
 * several consecutive buffers without restarting the cipher.</p>
 */
interface EtchCipherCursor {
	void transform(ByteBuffer input, ByteBuffer output) throws IOException;

	long transformLong(long value) throws IOException;
}
