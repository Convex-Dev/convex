package convex.etch;

import java.io.IOException;

/**
 * Internal memory-mapping backend for an Etch file.
 */
interface EtchFileMapper extends AutoCloseable {
	/**
	 * Gets a cursor at an absolute file position, expanding the mapping if needed.
	 */
	EtchCursor cursor(long position, long dataLength) throws IOException;

	/**
	 * Forces dirty mapped pages to persistent storage.
	 */
	void force() throws IOException;

	/**
	 * Human-readable backend name, primarily for diagnostics and tests.
	 */
	String implementationName();

	@Override
	void close() throws IOException;
}
