package convex.etch;

import convex.core.data.Hash;

/**
 * Constants shared by Etch file implementations and format tooling.
 *
 * <p>Public constants describe stable on-disk values that external readers,
 * validators and migration tools may need. Package-private constants are
 * implementation tuning parameters and are not part of the file format.</p>
 */
public final class EtchConstants {

	private EtchConstants() {
	}

	// File identity and supported versions

	/** Unsigned 16-bit magic number at the start of every Etch file. */
	public static final int MAGIC_NUMBER=0xe7c6;

	public static final short VERSION_1=1;
	public static final short VERSION_2=2;
	/** Reserved for the planned Etch v3 format; not yet accepted for opening. */
	public static final short VERSION_3=3;
	/** Version written by default for newly created Etch files. */
	public static final short CURRENT_VERSION=VERSION_2;

	// Header layout

	public static final int MAGIC_NUMBER_SIZE=Short.BYTES;
	public static final int VERSION_SIZE=Short.BYTES;
	public static final int DATA_LENGTH_SIZE=Long.BYTES;
	public static final int ROOT_HASH_SIZE=Hash.LENGTH;

	public static final long VERSION_OFFSET=MAGIC_NUMBER_SIZE;
	public static final long DATA_LENGTH_OFFSET=VERSION_OFFSET+VERSION_SIZE;
	public static final long ROOT_HASH_OFFSET=DATA_LENGTH_OFFSET+DATA_LENGTH_SIZE;

	public static final int COMMON_HEADER_SIZE=
			MAGIC_NUMBER_SIZE+VERSION_SIZE+DATA_LENGTH_SIZE+ROOT_HASH_SIZE;
	public static final int V1_HEADER_SIZE=COMMON_HEADER_SIZE;
	public static final int V2_HEADER_SIZE=64;
	/** Size of the immutable per-file salt in an Etch v3 header. */
	public static final int V3_FILE_SALT_SIZE=32;

	public static final long V1_INDEX_START=V1_HEADER_SIZE;
	public static final long V2_INDEX_START=V2_HEADER_SIZE;

	// Stored record and radix index layout

	public static final int KEY_SIZE=Hash.LENGTH;
	public static final int LABEL_SIZE=Byte.BYTES+Long.BYTES;
	public static final int ENCODING_LENGTH_SIZE=Short.BYTES;
	public static final int POINTER_SIZE=Long.BYTES;

	/** Maximum radix depth for a 32-byte hash key. */
	public static final int MAX_LEVEL=60;

	public static final int ROOT_INDEX_SIZE=1<<16;
	public static final int SECOND_LEVEL_INDEX_SIZE=1<<8;
	public static final int DEEP_INDEX_SIZE=1<<4;

	public static final long POINTER_TYPE_MASK=0xC000000000000000L;
	public static final long POINTER_PLAIN=0x0000000000000000L;
	public static final long POINTER_INDEX=0x4000000000000000L;
	public static final long POINTER_START=0x8000000000000000L;
	public static final long POINTER_CHAIN=0xC000000000000000L;

	// Configuration defaults

	public static final boolean DEFAULT_BUILD_CHAINS=true;

	// Mapper implementation tuning (not file-format values)

	static final long MAX_REGION_SIZE=1L<<30;
	static final long REGION_MARGIN=1L<<16;
}
