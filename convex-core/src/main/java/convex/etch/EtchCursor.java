package convex.etch;

import java.lang.invoke.VarHandle;

/**
 * A position-aware view over Etch's mapped storage.
 *
 * <p>The deliberately small API is available on Java 21. Runtime-specific
 * mapping implementations can therefore live behind it without leaking a
 * newer JDK API into Etch or its public bytecode.</p>
 */
interface EtchCursor {
	byte get();

	short getShort();

	long getLong();

	void get(byte[] destination);

	void get(byte[] destination, int offset, int length);

	void put(byte value);

	void putShort(short value);

	void putLong(long value);

	void put(byte[] source);

	void put(byte[] source, int offset, int length);

	/**
	 * Reads a published index slot and orders subsequent mapped reads after it.
	 *
	 * <p>Etch v1 indexes may be unaligned and use this fence fallback. The FFM
	 * backend is v2-only and overrides this operation with an aligned acquire.</p>
	 */
	default long getLongAcquire() {
		long value=getLong();
		VarHandle.acquireFence();
		return value;
	}

	/**
	 * Publishes an index slot after all preceding mapped writes.
	 * See {@link #getLongAcquire()} for the alignment constraint.
	 */
	default void putLongRelease(long value) {
		VarHandle.releaseFence();
		putLong(value);
	}
}
