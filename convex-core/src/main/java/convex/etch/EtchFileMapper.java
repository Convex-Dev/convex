package convex.etch;

import java.io.IOException;

/**
 * Internal physical memory-mapping backend for an Etch file.
 *
 * <p>Read operations never extend the underlying file. Write operations may
 * extend it according to the backend's mapping growth policy.</p>
 */
interface EtchFileMapper extends AutoCloseable {
	// Ordinary file transfers are deliberately bulk-only. EtchFileAccess owns
	// the header/data distinction and any future encryption transformation.
	void get(long position, byte[] destination, int offset, int length) throws IOException;

	/** Reads and transforms mapped bytes directly into the destination. */
	void getTransformed(long position, byte[] destination, int offset, int length,
			EtchCipherCursor cursor) throws IOException;

	/**
	 * Ensures the complete range is writable. Must be called once before one or
	 * more {@link #put(long, byte[], int, int)} operations fill that range.
	 */
	void ensureWriteCapacity(long position, long length) throws IOException;

	/**
	 * Writes into a range already prepared by {@link #ensureWriteCapacity(long, long)}.
	 */
	void put(long position, byte[] source, int offset, int length) throws IOException;

	/** Transforms source bytes directly into an already prepared mapped range. */
	void putTransformed(long position, byte[] source, int offset, int length,
			EtchCipherCursor cursor) throws IOException;

	/**
	 * Reads a published index slot and orders subsequent mapped reads after it.
	 */
	long readIndexSlotAcquire(long position) throws IOException;

	/**
	 * Publishes an index slot after all preceding mapped writes.
	 */
	void writeIndexSlotRelease(long position, long value) throws IOException;

	/**
	 * Forces dirty mapped pages and the backing file contents to persistent storage.
	 * A successful return is the durability boundary exposed to higher layers.
	 */
	void force() throws IOException;

	/**
	 * Forces dirty mapped pages intersecting an already mapped file range.
	 * Unlike {@link #force()}, this does not force unrelated mappings or file
	 * metadata. It is used internally after an Etch v3 header-copy write, once
	 * the preceding full-file durability barrier has completed.
	 */
	void forceRange(long position, long length) throws IOException;

	/**
	 * Human-readable backend name, primarily for diagnostics and tests.
	 */
	String implementationName();

	@Override
	void close() throws IOException;
}
