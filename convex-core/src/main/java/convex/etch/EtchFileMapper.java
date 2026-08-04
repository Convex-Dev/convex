package convex.etch;

import java.io.IOException;

/**
 * Internal memory-mapping backend for an Etch file.
 */
interface EtchFileMapper extends AutoCloseable {
	byte getByte(long position) throws IOException;

	short getShort(long position) throws IOException;

	long getLong(long position) throws IOException;

	void get(long position, byte[] destination, int offset, int length) throws IOException;

	void putByte(long position, byte value) throws IOException;

	void putShort(long position, short value) throws IOException;

	void putLong(long position, long value) throws IOException;

	void put(long position, byte[] source, int offset, int length) throws IOException;

	/**
	 * Reads a published index slot and orders subsequent mapped reads after it.
	 */
	default long getLongAcquire(long position) throws IOException {
		long value=getLong(position);
		java.lang.invoke.VarHandle.acquireFence();
		return value;
	}

	/**
	 * Publishes an index slot after all preceding mapped writes.
	 */
	default void putLongRelease(long position, long value) throws IOException {
		java.lang.invoke.VarHandle.releaseFence();
		putLong(position,value);
	}

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
